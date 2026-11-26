package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class MachineAlerts {
    private static final String ALERT_FROM =
            "maintenance.alerts a JOIN maintenance.machines m ON m.id = a.machine_id";

    private final JdbcClient db;
    private final Clock clock;
    private final AuditLog audit;

    MachineAlerts(JdbcClient db, Clock clock, AuditLog audit) {
        this.db = db;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Machine> machines(int page, int size, String code, MachineStatus status) {
        var query = new PagedQuery<>("maintenance.machines m", "m.*", "m.machine_code, m.id", MachineAlerts::mapMachine);
        query.filter("m.machine_code", code);
        query.filter("m.status", status);
        return query.run(db, page, size);
    }

    Machine machine(UUID id) {
        return machine(id, false);
    }

    Machine requireMachine(UUID id, String claimedCode, boolean lock) {
        Machine found = machine(id, lock);
        if (!found.machineCode().equals(claimedCode)) {
            throw ApiProblem.badRequest("The machine UUID and code identify different machines.");
        }
        return found;
    }

    private Machine machine(UUID id, boolean lock) {
        return db.sql("SELECT * FROM maintenance.machines WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(MachineAlerts::mapMachine).optional()
                .orElseThrow(() -> ApiProblem.notFound("Machine not found."));
    }

    @Transactional
    public Machine createMachine(MachineCreate request, Actor actor) {
        UUID id = UUID.randomUUID();
        int inserted = db.sql("""
                INSERT INTO maintenance.machines (id, machine_code, name, location, model, status, created_at, updated_at)
                VALUES (:id, :code, :name, :location, :model, 'OFFLINE', :now, :now)
                ON CONFLICT (machine_code) DO NOTHING
                """).param("id", id).param("code", request.machineCode()).param("name", request.name())
                .param("location", request.location()).param("model", request.model()).param("now", clockTime()).update();
        if (inserted == 0) {
            throw ApiProblem.conflict("A machine with that code already exists.");
        }
        audit.record(actor.id(), "REGISTER_MACHINE", id.toString(), true, actor.source());
        return machine(id);
    }

    MachineObservation observation(UUID id) {
        return db.sql("SELECT id, machine_code, status, observed_at FROM maintenance.machines WHERE id = :id AND observed_at IS NOT NULL")
                .param("id", id).query((row, index) -> new MachineObservation(id, row.getString("machine_code"),
                        MachineStatus.valueOf(row.getString("status")), DbTime.instant(row, "observed_at")))
                .optional().orElseThrow(() -> ApiProblem.notFound("No status observation exists for this machine."));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Alert> alerts(int page, int size, UUID machineId, String code, AlertStatus status, AlertSeverity severity) {
        var query = new PagedQuery<>(ALERT_FROM, "a.*, m.machine_code", "a.occurred_at DESC, a.id", MachineAlerts::mapAlert);
        query.filter("a.machine_id", machineId);
        query.filter("m.machine_code", code);
        query.filter("a.status", status);
        query.filter("a.severity", severity);
        return query.run(db, page, size);
    }

    Alert alert(UUID id) {
        return alert(id, false);
    }

    Alert lockAlert(UUID id) {
        return alert(id, true);
    }

    private Alert alert(UUID id, boolean lock) {
        return db.sql("SELECT a.*, m.machine_code FROM " + ALERT_FROM + " WHERE a.id = :id" + (lock ? " FOR UPDATE OF a" : ""))
                .param("id", id).query(MachineAlerts::mapAlert).optional()
                .orElseThrow(() -> ApiProblem.notFound("Alert not found."));
    }

    @Transactional
    public Creation<Alert> createAlert(AlertCreate request) {
        if (!Double.isFinite(request.observedValue()) || !Double.isFinite(request.thresholdValue())) {
            throw ApiProblem.badRequest("Alert metrics must be finite numbers.");
        }
        OffsetDateTime occurred;
        try {
            occurred = OffsetDateTime.parse(request.occurredAt());
        } catch (DateTimeParseException invalidDate) {
            throw ApiProblem.badRequest("occurredAt must be a valid UTC date-time.");
        }

        Machine machine = requireMachine(request.machineId(), request.machineCode(), true);
        var existing = db.sql("SELECT a.*, m.machine_code FROM " + ALERT_FROM + """
                 WHERE a.machine_id = :machine AND a.metric_type = :metric AND a.status IN ('ACTIVE', 'ACKNOWLEDGED')
                """).param("machine", machine.id()).param("metric", request.metricType().name())
                .query(MachineAlerts::mapAlert).optional();
        if (existing.isPresent()) {
            return new Creation<>(existing.get(), false);
        }

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO maintenance.alerts (id, machine_id, metric_type, severity, observed_value,
                    threshold_value, message, occurred_at, status)
                VALUES (:id, :machine, :metric, :severity, :value, :threshold, :message, :occurred, 'ACTIVE')
                """).param("id", id).param("machine", machine.id()).param("metric", request.metricType().name())
                .param("severity", request.severity().name()).param("value", request.observedValue())
                .param("threshold", request.thresholdValue()).param("message", request.message())
                .param("occurred", occurred).update();
        db.sql("""
                UPDATE maintenance.machines SET observed_at = :occurred, status = :status, updated_at = :now
                WHERE id = :id AND (observed_at IS NULL OR observed_at < :occurred)
                """).param("id", machine.id()).param("occurred", occurred)
                .param("status", request.severity().name()).param("now", clockTime()).update();
        return new Creation<>(alert(id), true);
    }

    @Transactional
    public Alert acknowledge(UUID id) {
        Alert current = lockAlert(id);
        if (current.status() == AlertStatus.RESOLVED) {
            throw ApiProblem.conflict("A resolved alert cannot be acknowledged.");
        }
        if (current.status() == AlertStatus.ACKNOWLEDGED) {
            return current;
        }
        db.sql("UPDATE maintenance.alerts SET status = 'ACKNOWLEDGED', acknowledged_at = :now WHERE id = :id")
                .param("id", id).param("now", clockTime()).update();
        return alert(id);
    }

    @Transactional
    public Alert resolve(UUID id) {
        Alert current = lockAlert(id);
        if (current.status() == AlertStatus.RESOLVED) {
            return current;
        }
        db.sql("UPDATE maintenance.alerts SET status = 'RESOLVED', resolved_at = :now WHERE id = :id")
                .param("id", id).param("now", clockTime()).update();
        return alert(id);
    }

    private OffsetDateTime clockTime() {
        return OffsetDateTime.now(clock);
    }

    private static Machine mapMachine(ResultSet row, int index) throws SQLException {
        return new Machine(row.getObject("id", UUID.class), row.getString("machine_code"), row.getString("name"),
                row.getString("location"), row.getString("model"), MachineStatus.valueOf(row.getString("status")),
                DbTime.instant(row, "created_at"), DbTime.instant(row, "updated_at"));
    }

    private static Alert mapAlert(ResultSet row, int index) throws SQLException {
        return new Alert(row.getObject("id", UUID.class), row.getObject("machine_id", UUID.class),
                row.getString("machine_code"), MetricType.valueOf(row.getString("metric_type")),
                AlertSeverity.valueOf(row.getString("severity")), row.getDouble("observed_value"),
                row.getDouble("threshold_value"), row.getString("message"), DbTime.instant(row, "occurred_at"),
                AlertStatus.valueOf(row.getString("status")), DbTime.instant(row, "acknowledged_at"),
                DbTime.instant(row, "resolved_at"));
    }
}
