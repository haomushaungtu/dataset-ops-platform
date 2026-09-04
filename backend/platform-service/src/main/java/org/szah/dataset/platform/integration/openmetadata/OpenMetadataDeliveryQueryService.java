package org.szah.dataset.platform.integration.openmetadata;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.szah.dataset.platform.common.api.BusinessException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OpenMetadataDeliveryQueryService {
    private final JdbcClient jdbc;

    public OpenMetadataDeliveryQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public OpenMetadataDeliveryView get(UUID eventId) {
        DeliveryRow row = jdbc.sql(BASE_QUERY + " AND e.event_id = :eventId")
                .param("eventId", eventId)
                .query(this::mapDelivery)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "OPENMETADATA_DELIVERY_NOT_FOUND", "OpenMetadata 投递记录不存在", HttpStatus.NOT_FOUND));
        return toView(row);
    }

    public List<OpenMetadataDeliveryView> terminalFailures(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbc.sql(BASE_QUERY + " AND e.failed_at IS NOT NULL ORDER BY e.failed_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(this::mapDelivery)
                .list()
                .stream()
                .map(this::toView)
                .toList();
    }

    private OpenMetadataDeliveryView toView(DeliveryRow row) {
        List<OpenMetadataDeliveryView.Attempt> history = jdbc.sql("""
                SELECT attempt_no, outcome, error_code, error_message, attempted_at, next_attempt_at
                  FROM integration.outbox_delivery_attempt
                 WHERE event_id = :eventId
                 ORDER BY attempt_no
                """)
                .param("eventId", row.eventId())
                .query((rs, index) -> new OpenMetadataDeliveryView.Attempt(
                        rs.getInt("attempt_no"),
                        rs.getString("outcome"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        rs.getObject("attempted_at", OffsetDateTime.class),
                        rs.getObject("next_attempt_at", OffsetDateTime.class)))
                .list();
        return new OpenMetadataDeliveryView(
                row.eventId(), row.businessId(), row.externalFqn(), row.externalId(),
                row.externalVersion(), row.syncStatus(), row.attempts(), row.nextAttemptAt(),
                row.publishedAt(), row.failedAt(), row.lastErrorCode(), row.lastErrorMessage(), history);
    }

    private DeliveryRow mapDelivery(ResultSet rs, int index) throws SQLException {
        return new DeliveryRow(
                rs.getObject("event_id", UUID.class),
                rs.getObject("business_id", UUID.class),
                rs.getString("external_fqn"),
                rs.getString("external_id"),
                rs.getString("external_version"),
                rs.getString("sync_status"),
                rs.getInt("publish_attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("failed_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"));
    }

    private static final String BASE_QUERY = """
            SELECT e.event_id, e.publish_attempts, e.next_attempt_at, e.published_at, e.failed_at,
                   e.last_error_code, e.last_error_message, m.business_id, m.external_fqn,
                   m.external_id, m.external_version, m.sync_status
              FROM integration.outbox_event e
              JOIN integration.external_resource_mapping m ON m.source_event_id = e.event_id
             WHERE e.event_type = 'dataset.version.openmetadata-sync-requested.v1'
            """;

    private record DeliveryRow(
            UUID eventId,
            UUID businessId,
            String externalFqn,
            String externalId,
            String externalVersion,
            String syncStatus,
            int attempts,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime publishedAt,
            OffsetDateTime failedAt,
            String lastErrorCode,
            String lastErrorMessage) {
    }
}
