package com.kestrel.inventory;

import static com.kestrel.inventory.soap.InventoryFaultCode.*;

import com.kestrel.inventory.soap.ReservationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryStock {
    record Part(UUID id, String sku, String name, String description, String unit, int available, int reorderLevel) {}

    record Reservation(UUID id, UUID partId, UUID workOrderId, int quantity, ReservationStatus status,
                       OffsetDateTime releasedAt) {}

    private static final long REQUEST_LOCK_NAMESPACE = 0x494e56454e544f52L;
    private final JdbcClient db;
    private final Clock clock;

    InventoryStock(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Part getPart(UUID id) {
        return part(id, false);
    }

    @Transactional
    public Reservation reserve(UUID partId, int quantity, UUID workOrderId, UUID requestId) {
        // Serialize retries even when the same request ID is sent with a different part.
        long key = requestId.getMostSignificantBits() ^ requestId.getLeastSignificantBits() ^ REQUEST_LOCK_NAMESPACE;
        db.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query((row, index) -> true).single();

        var existing = db.sql("SELECT * FROM inventory.part_reservations WHERE request_id = :request FOR UPDATE")
                .param("request", requestId).query(InventoryStock::mapReservation).optional();
        if (existing.isPresent()) {
            Reservation prior = existing.get();
            if (!prior.partId().equals(partId) || !prior.workOrderId().equals(workOrderId) || prior.quantity() != quantity) {
                throw new InventoryProblem(REQUEST_CONFLICT, "This request ID already belongs to a different reservation.");
            }
            return prior;
        }

        Part part = part(partId, true);
        if (part.available() < quantity) {
            throw new InventoryProblem(INSUFFICIENT_STOCK, "There is insufficient stock to reserve that quantity.",
                    part.available());
        }
        db.sql("UPDATE inventory.spare_parts SET available_quantity = available_quantity - :quantity WHERE id = :id")
                .param("quantity", quantity).param("id", partId).update();

        UUID reservationId = UUID.randomUUID();
        var now = OffsetDateTime.now(clock);
        db.sql("""
                INSERT INTO inventory.part_reservations
                    (id, part_id, work_order_id, request_id, quantity, status, created_at)
                VALUES (:id, :part, :order, :request, :quantity, 'RESERVED', :now)
                """).param("id", reservationId).param("part", partId).param("order", workOrderId)
                .param("request", requestId).param("quantity", quantity).param("now", now).update();
        movement(partId, reservationId, "RESERVE", quantity, now);
        return reservation(reservationId, false);
    }

    @Transactional
    public Reservation release(UUID id, String reason) {
        Reservation current = reservation(id, true);
        if (current.status() == ReservationStatus.RELEASED) {
            return current;
        }

        var now = OffsetDateTime.now(clock);
        db.sql("UPDATE inventory.spare_parts SET available_quantity = available_quantity + :quantity WHERE id = :part")
                .param("part", current.partId()).param("quantity", current.quantity()).update();
        db.sql("""
                UPDATE inventory.part_reservations SET status = 'RELEASED', released_at = :now, release_reason = :reason
                WHERE id = :id
                """).param("now", now).param("reason", reason).param("id", id).update();
        movement(current.partId(), id, "RELEASE", current.quantity(), now);
        return reservation(id, false);
    }

    @Transactional
    public UUID purchaseOrder(UUID partId, int quantity, String supplierReference) {
        getPart(partId);
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO inventory.purchase_orders (id, part_id, quantity, supplier_reference, status, created_at)
                VALUES (:id, :part, :quantity, :supplier, 'CREATED', :now)
                """).param("id", id).param("part", partId).param("quantity", quantity)
                .param("supplier", supplierReference).param("now", OffsetDateTime.now(clock)).update();
        return id;
    }

    private Part part(UUID id, boolean lock) {
        return db.sql("SELECT * FROM inventory.spare_parts WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((row, index) -> new Part(id, row.getString("sku"), row.getString("name"),
                        row.getString("description"), row.getString("unit"), row.getInt("available_quantity"),
                        row.getInt("reorder_level")))
                .optional().orElseThrow(() -> new InventoryProblem(UNKNOWN_PART, "Spare part not found."));
    }

    private Reservation reservation(UUID id, boolean lock) {
        return db.sql("SELECT * FROM inventory.part_reservations WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(InventoryStock::mapReservation).optional()
                .orElseThrow(() -> new InventoryProblem(UNKNOWN_RESERVATION, "Reservation not found."));
    }

    private void movement(UUID partId, UUID reservationId, String movement, int quantity, OffsetDateTime timestamp) {
        db.sql("""
                INSERT INTO inventory.stock_movements (id, part_id, reservation_id, movement_type, quantity, occurred_at)
                VALUES (:id, :part, :reservation, :type, :quantity, :now)
                """).param("id", UUID.randomUUID()).param("part", partId).param("reservation", reservationId)
                .param("type", movement).param("quantity", quantity).param("now", timestamp).update();
    }

    private static Reservation mapReservation(ResultSet row, int index) throws SQLException {
        return new Reservation(row.getObject("id", UUID.class), row.getObject("part_id", UUID.class),
                row.getObject("work_order_id", UUID.class), row.getInt("quantity"),
                ReservationStatus.valueOf(row.getString("status")), row.getObject("released_at", OffsetDateTime.class));
    }
}
