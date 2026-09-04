package org.szah.dataset.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "platform.identity-sync.enabled", havingValue = "true")
public class IdentityRoleOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(IdentityRoleOutboxPublisher.class);
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RestClient identity;
    private final TransactionTemplate transactions;
    private final String clientId;
    private final String clientSecret;

    public IdentityRoleOutboxPublisher(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            RestClient.Builder restClient,
            PlatformTransactionManager transactionManager,
            @Value("${platform.identity-sync.base-url}") String baseUrl,
            @Value("${platform.identity-sync.client-id}") String clientId,
            @Value("${platform.identity-sync.client-secret}") String clientSecret) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.identity = restClient.baseUrl(baseUrl).build();
        this.transactions = new TransactionTemplate(transactionManager);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Scheduled(initialDelayString = "${platform.identity-sync.initial-delay:PT5S}",
            fixedDelayString = "${platform.identity-sync.fixed-delay:PT5S}")
    public void publish() {
        for (int processed = 0; processed < 10
                && Boolean.TRUE.equals(transactions.execute(status -> publishOne())); processed++) {
            // Bound every poll so one event stream cannot monopolize the scheduler.
        }
    }

    boolean publishOne() {
        Optional<OutboxRow> candidate = jdbc.sql("""
                SELECT event_id, aggregate_id, payload, publish_attempts
                  FROM integration.outbox_event
                 WHERE event_type = 'supplier.identity-role.requested.v1'
                   AND published_at IS NULL
                   AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                 ORDER BY occurred_at
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """)
                .param("now", OffsetDateTime.now())
                .query((rs, row) -> new OutboxRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("publish_attempts")))
                .optional();
        if (candidate.isEmpty()) {
            return false;
        }
        OutboxRow event = candidate.get();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(event.payload(), Map.class);
            String userId = String.valueOf(payload.get("user_id"));
            grantSupplierRole(userId);
            OffsetDateTime now = OffsetDateTime.now();
            jdbc.sql("""
                    UPDATE integration.outbox_event
                       SET published_at = :now, publish_attempts = publish_attempts + 1,
                           next_attempt_at = NULL
                     WHERE event_id = :eventId
                    """).param("now", now).param("eventId", event.eventId()).update();
            jdbc.sql("UPDATE business.supplier SET identity_sync_status = 'SYNCED', updated_at = :now " +
                            "WHERE application_id = :applicationId")
                    .param("now", now).param("applicationId", event.applicationId()).update();
        } catch (Exception exception) {
            int attempts = event.attempts() + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
            jdbc.sql("""
                    UPDATE integration.outbox_event
                       SET publish_attempts = publish_attempts + 1, next_attempt_at = :nextAttempt
                     WHERE event_id = :eventId
                    """)
                    .param("nextAttempt", OffsetDateTime.now().plusSeconds(delaySeconds))
                    .param("eventId", event.eventId())
                    .update();
            jdbc.sql("UPDATE business.supplier SET identity_sync_status = 'FAILED' " +
                            "WHERE application_id = :applicationId")
                    .param("applicationId", event.applicationId()).update();
            log.warn("Identity role synchronization failed for outbox event {}; retry scheduled",
                    event.eventId());
        }
        return true;
    }

    private void grantSupplierRole(String userId) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "platform.internal");
        @SuppressWarnings("unchecked")
        Map<String, Object> token = identity.post()
                .uri("/oauth2/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (token == null || !(token.get("access_token") instanceof String accessToken)) {
            throw new IllegalStateException("identity token response did not contain an access token");
        }
        identity.put()
                .uri("/api/v1/internal/subjects/{subject}/roles/supplier", userId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .toBodilessEntity();
    }

    private record OutboxRow(UUID eventId, UUID applicationId, String payload, int attempts) {
    }
}
