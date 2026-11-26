package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkOrders {
    private static final String FROM = """
            maintenance.work_orders o JOIN maintenance.machines m ON m.id = o.machine_id
            LEFT JOIN maintenance.reservation_references r ON r.reservation_id = o.reservation_id
            """;
    private static final String COLUMNS = "o.*, m.machine_code, r.status AS reservation_status";

    private final JdbcClient db;
    private final MachineAlerts machines;
    private final AuditLog audit;
    private final Clock clock;

    WorkOrders(JdbcClient db, MachineAlerts machines, AuditLog audit, Clock clock) {
        this.db = db;
        this.machines = machines;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<WorkOrder> list(int page, int size, UUID machineId, String code, WorkStatus status,
                                UUID technicianId, Actor actor) {
        var query = new PagedQuery<>(FROM, COLUMNS, "o.created_at DESC, o.id", WorkOrders::order);
        query.filter("o.machine_id", machineId);
        query.filter("m.machine_code", code);
        query.filter("o.status", status);
        query.filter("o.technician_id", technicianId);
        if (actor.technician()) {
            UUID assigned = technicianFor(actor);
            if (assigned == null) {
                return new Page<>(java.util.List.of(), page, size, 0);
            }
            query.filter("o.technician_id", assigned);
        }
        return query.run(db, page, size);
    }

    WorkOrder get(UUID id, Actor actor) {
        WorkOrder current = get(id, false);
        checkOwnership(current, actor);
        return current;
    }

    @Transactional
    public Creation<WorkOrder> create(WorkOrderCreate request, Actor actor) {
        Machine machine = machines.requireMachine(request.machineId(), request.machineCode(), false);
        if (request.alertId() != null) {
            Alert alert = machines.lockAlert(request.alertId());
            if (!machine.id().equals(alert.machineId())) {
                throw ApiProblem.badRequest("The alert belongs to a different machine.");
            }

            var existing = db.sql("SELECT " + COLUMNS + " FROM " + FROM + " WHERE o.alert_id = :alert")
                    .param("alert", alert.id()).query(WorkOrders::order).optional();
            if (existing.isPresent()) {
                WorkOrder previous = existing.get();
                if (request.title().equals(previous.title()) && request.description().equals(previous.description())
                        && request.priority() == previous.priority()
                        && Objects.equals(request.partRequirement(), previous.partRequirement())) {
                    return new Creation<>(previous, false);
                }
                throw ApiProblem.conflict("The alert already has a work order with different details.");
            }
        }

        PartRequirement part = request.partRequirement();
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO maintenance.work_orders (id, machine_id, alert_id, title, description, priority,
                    status, part_id, part_quantity, created_at, updated_at)
                VALUES (:id, :machine, :alert, :title, :description, :priority, 'OPEN', :part, :quantity, :now, :now)
                """).param("id", id).param("machine", machine.id()).param("alert", request.alertId())
                .param("title", request.title()).param("description", request.description())
                .param("priority", request.priority().name()).param("part", part == null ? null : part.partId())
                .param("quantity", part == null ? null : part.quantity()).param("now", now()).update();
        record(actor, "CREATE_WORK_ORDER", id);
        return new Creation<>(get(id, false), true);
    }

    @Transactional
    public WorkOrder status(UUID id, WorkStatus next, Actor actor) {
        WorkOrder current = get(id, true);
        checkOwnership(current, actor);
        if (actor.technician() && next != WorkStatus.IN_PROGRESS && next != WorkStatus.COMPLETED) {
            throw ApiProblem.forbidden();
        }

        WorkOrderRules.requireStatusChange(current, next);
        if (current.status() == next) {
            return current;
        }
        db.sql("UPDATE maintenance.work_orders SET status = :status, updated_at = :now WHERE id = :id")
                .param("id", id).param("status", next.name()).param("now", now()).update();
        record(actor, "WORK_ORDER_" + next.name(), id);
        return get(id, false);
    }

    @Transactional
    public WorkOrder assign(UUID id, UUID technicianId, Actor actor) {
        WorkOrder current = get(id, true);
        if (current.status().terminal()) {
            throw ApiProblem.conflict("A terminal work order cannot be reassigned.");
        }
        boolean active = db.sql("SELECT active FROM maintenance.technicians WHERE id = :id")
                .param("id", technicianId).query(Boolean.class).optional()
                .orElseThrow(() -> ApiProblem.notFound("Technician not found."));
        if (!active) {
            throw ApiProblem.conflict("The technician is inactive.");
        }
        if (technicianId.equals(current.technicianId())) {
            return current;
        }
        db.sql("UPDATE maintenance.work_orders SET technician_id = :tech, updated_at = :now WHERE id = :id")
                .param("id", id).param("tech", technicianId).param("now", now()).update();
        record(actor, "ASSIGN_TECHNICIAN", id);
        return get(id, false);
    }

    @Transactional
    public WorkOrder reservation(UUID id, ReservationUpdate update, Actor actor) {
        WorkOrder current = get(id, true);
        UUID reservationId = update.reservationId();
        boolean currentId = reservationId.equals(current.reservationId());
        if (currentId && update.status() == current.reservationStatus()) {
            return current;
        }
        if (current.status().terminal()) {
            throw ApiProblem.conflict("A terminal work order cannot change its reservation.");
        }
        if (update.status() == ReservationStatus.RELEASED) {
            if (!currentId || current.reservationStatus() != ReservationStatus.RESERVED) {
                throw ApiProblem.conflict("Release must reference the active reservation on this order.");
            }
            db.sql("UPDATE maintenance.reservation_references SET status = 'RELEASED' WHERE reservation_id = :reservation")
                    .param("reservation", reservationId).update();
            db.sql("UPDATE maintenance.work_orders SET updated_at = :now WHERE id = :id")
                    .param("id", id).param("now", now()).update();
        } else {
            WorkOrderRules.requireNewReservation(current);
            int inserted = db.sql("""
                    INSERT INTO maintenance.reservation_references (reservation_id, work_order_id, status)
                    VALUES (:reservation, :id, 'RESERVED') ON CONFLICT (reservation_id) DO NOTHING
                    """).param("reservation", reservationId).param("id", id).update();
            if (inserted == 0) {
                throw ApiProblem.conflict("The reservation UUID has already been recorded.");
            }
            db.sql("""
                    UPDATE maintenance.work_orders SET reservation_id = :reservation,
                        status = 'READY_FOR_MAINTENANCE', updated_at = :now WHERE id = :id
                    """).param("reservation", reservationId).param("id", id).param("now", now()).update();
        }
        record(actor, "RESERVATION_" + update.status().name(), id);
        return get(id, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Technician> technicians(int page, int size, Boolean active) {
        var query = new PagedQuery<>("maintenance.technicians t", "t.*", "t.full_name, t.id",
                (row, index) -> new Technician(row.getObject("id", UUID.class), row.getString("full_name"),
                        row.getString("email"), row.getBoolean("active")));
        query.filter("t.active", active);
        return query.run(db, page, size);
    }

    private void checkOwnership(WorkOrder current, Actor actor) {
        if (actor.technician() && (current.technicianId() == null || !current.technicianId().equals(technicianFor(actor)))) {
            throw ApiProblem.forbidden();
        }
    }

    private UUID technicianFor(Actor actor) {
        return db.sql("SELECT id FROM maintenance.technicians WHERE user_id = :user AND active")
                .param("user", actor.id()).query(UUID.class).optional().orElse(null);
    }

    private WorkOrder get(UUID id, boolean lock) {
        if (lock) {
            db.sql("SELECT id FROM maintenance.work_orders WHERE id = :id FOR UPDATE")
                    .param("id", id).query(UUID.class).optional()
                    .orElseThrow(() -> ApiProblem.notFound("Work order not found."));
        }
        return db.sql("SELECT " + COLUMNS + " FROM " + FROM + " WHERE o.id = :id")
                .param("id", id).query(WorkOrders::order).optional()
                .orElseThrow(() -> ApiProblem.notFound("Work order not found."));
    }

    private void record(Actor actor, String action, UUID id) {
        audit.record(actor.id(), action, id.toString(), true, actor.source());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static WorkOrder order(ResultSet row, int index) throws SQLException {
        UUID part = row.getObject("part_id", UUID.class);
        String reservation = row.getString("reservation_status");
        return new WorkOrder(row.getObject("id", UUID.class), row.getObject("machine_id", UUID.class),
                row.getString("machine_code"), row.getObject("alert_id", UUID.class), row.getString("title"),
                row.getString("description"), Priority.valueOf(row.getString("priority")),
                part == null ? null : new PartRequirement(part, row.getInt("part_quantity")),
                WorkStatus.valueOf(row.getString("status")), row.getObject("technician_id", UUID.class),
                row.getObject("reservation_id", UUID.class), reservation == null ? null : ReservationStatus.valueOf(reservation),
                DbTime.instant(row, "created_at"), DbTime.instant(row, "updated_at"));
    }
}
