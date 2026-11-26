package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
class AuditLog {
    private final JdbcClient db;
    private final Clock clock;

    AuditLog(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    void record(UUID actorId, String action, String target, boolean success, String source) {
        db.sql("""
                INSERT INTO maintenance.audit_events (id, actor_id, action, target, result, timestamp, source)
                VALUES (:id, :actor, :action, :target, :result, :timestamp, :source)
                """)
                .param("id", UUID.randomUUID()).param("actor", actorId).param("action", action)
                .param("target", target).param("result", success ? "SUCCESS" : "FAILURE")
                .param("timestamp", OffsetDateTime.now(clock)).param("source", source).update();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AuditEvent> list(int page, int size) {
        return new PagedQuery<>("maintenance.audit_events e", "e.*", "e.timestamp DESC, e.id",
                (rs, row) -> new AuditEvent(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("action"), rs.getString("target"),
                        rs.getString("result"), DbTime.instant(rs, "timestamp"), rs.getString("source")))
                .run(db, page, size);
    }
}
