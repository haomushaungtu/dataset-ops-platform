package org.szah.dataset.identity.audit;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String targetType, String targetId, String outcome) {
        jdbc.update("""
                insert into iam_audit_event
                  (id, occurred_at, actor, action, target_type, target_id, outcome)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), OffsetDateTime.now(), actor, action,
                targetType, targetId, outcome);
    }
}
