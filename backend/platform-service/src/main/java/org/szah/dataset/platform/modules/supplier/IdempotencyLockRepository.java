package org.szah.dataset.platform.modules.supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;

@Repository
class IdempotencyLockRepository {
    private final JdbcClient jdbc;
    private final boolean postgresql;

    IdempotencyLockRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        try (var connection = dataSource.getConnection()) {
            this.postgresql = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException exception) {
            throw new IllegalStateException("无法识别幂等锁数据库类型", exception);
        }
    }

    void lock(String actorId, String scope, String key, OffsetDateTime now) {
        if (postgresql) {
            jdbc.sql("""
                    INSERT INTO integration.idempotency_lock
                        (actor_id, operation_scope, idempotency_key, created_at)
                    VALUES (:actorId, :scope, :key, :createdAt)
                    ON CONFLICT (actor_id, operation_scope, idempotency_key) DO NOTHING
                    """)
                    .param("actorId", actorId)
                    .param("scope", scope)
                    .param("key", key)
                    .param("createdAt", now)
                    .update();
        } else {
            jdbc.sql("""
                    MERGE INTO integration.idempotency_lock
                        (actor_id, operation_scope, idempotency_key, created_at)
                    KEY (actor_id, operation_scope, idempotency_key)
                    VALUES (:actorId, :scope, :key, :createdAt)
                    """)
                    .param("actorId", actorId)
                    .param("scope", scope)
                    .param("key", key)
                    .param("createdAt", now)
                    .update();
        }

        jdbc.sql("""
                SELECT actor_id FROM integration.idempotency_lock
                 WHERE actor_id = :actorId AND operation_scope = :scope AND idempotency_key = :key
                 FOR UPDATE
                """)
                .param("actorId", actorId)
                .param("scope", scope)
                .param("key", key)
                .query(String.class)
                .single();
    }
}
