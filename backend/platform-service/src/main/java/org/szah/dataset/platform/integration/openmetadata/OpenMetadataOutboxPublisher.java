package org.szah.dataset.platform.integration.openmetadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "platform.openmetadata-sync.enabled", havingValue = "true")
public class OpenMetadataOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OpenMetadataOutboxPublisher.class);
    private static final int POLL_BATCH_SIZE = 10;
    private static final String UPSERT_PATH = "/api/v1/openmetadata/dataset-versions:upsert";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RestClient iam;
    private final RestClient adapter;
    private final TransactionTemplate transactions;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final int maxAttempts;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Clock clock;

    @Autowired
    public OpenMetadataOutboxPublisher(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            RestClient.Builder restClient,
            PlatformTransactionManager transactionManager,
            @Value("${platform.openmetadata-sync.adapter-base-url}") String adapterBaseUrl,
            @Value("${platform.openmetadata-sync.token-uri}") String tokenUri,
            @Value("${platform.openmetadata-sync.client-id}") String clientId,
            @Value("${platform.openmetadata-sync.client-secret}") String clientSecret,
            @Value("${platform.openmetadata-sync.scope:platform.internal}") String scope,
            @Value("${platform.openmetadata-sync.max-attempts:6}") int maxAttempts,
            @Value("${platform.openmetadata-sync.retry-base-delay:PT2S}") Duration retryBaseDelay,
            @Value("${platform.openmetadata-sync.retry-max-delay:PT5M}") Duration retryMaxDelay) {
        this(jdbc, objectMapper, restClient, transactionManager, adapterBaseUrl, tokenUri, clientId,
                clientSecret, scope, maxAttempts, retryBaseDelay, retryMaxDelay, Clock.systemUTC());
    }

    OpenMetadataOutboxPublisher(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            RestClient.Builder restClient,
            PlatformTransactionManager transactionManager,
            String adapterBaseUrl,
            String tokenUri,
            String clientId,
            String clientSecret,
            String scope,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (retryBaseDelay.isNegative() || retryBaseDelay.isZero()
                || retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("retry delays are invalid");
        }
        requireHttpUri("adapterBaseUrl", adapterBaseUrl);
        requireHttpUri("tokenUri", tokenUri);
        requireText("clientId", clientId);
        requireText("clientSecret", clientSecret);
        requireText("scope", scope);
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.iam = restClient.clone().build();
        this.adapter = restClient.clone().baseUrl(stripTrailingSlash(adapterBaseUrl)).build();
        this.transactions = new TransactionTemplate(transactionManager);
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.maxAttempts = maxAttempts;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${platform.openmetadata-sync.initial-delay:PT5S}",
            fixedDelayString = "${platform.openmetadata-sync.fixed-delay:PT5S}")
    public void publish() {
        for (int processed = 0; processed < POLL_BATCH_SIZE
                && Boolean.TRUE.equals(transactions.execute(status -> publishOne())); processed++) {
            // Bound every poll so this integration cannot monopolize the scheduler.
        }
    }

    boolean publishOne() {
        OffsetDateTime now = now();
        Optional<OutboxRow> candidate = jdbc.sql("""
                SELECT event_id, payload, publish_attempts
                  FROM integration.outbox_event
                 WHERE event_type = :eventType
                   AND published_at IS NULL
                   AND failed_at IS NULL
                   AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                 ORDER BY occurred_at
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """)
                .param("eventType", OpenMetadataSyncOutboxService.EVENT_TYPE)
                .param("now", now)
                .query((rs, row) -> new OutboxRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("publish_attempts")))
                .optional();
        if (candidate.isEmpty()) {
            return false;
        }

        OutboxRow event = candidate.get();
        int attempt = event.attempts() + 1;
        AdapterSyncResult response;
        try {
            OpenMetadataSyncRequest request = objectMapper.readValue(
                    event.payload(), OpenMetadataSyncRequest.class);
            response = deliver(request);
            verifyResponse(request, response);
        } catch (Exception exception) {
            DeliveryFailure failure = classify(exception);
            boolean terminal = !failure.retryable() || attempt >= maxAttempts;
            markFailed(event.eventId(), attempt, failure, terminal, now());
            log.warn("OpenMetadata delivery {} failed with {}; {}",
                    event.eventId(), failure.code(), terminal ? "marked terminal" : "retry scheduled");
            return true;
        }
        // Database errors must escape and roll back the claim; retrying the idempotent remote upsert is safe.
        markSucceeded(event.eventId(), attempt, response, now());
        return true;
    }

    private AdapterSyncResult deliver(OpenMetadataSyncRequest request) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", scope);
        @SuppressWarnings("unchecked")
        Map<String, Object> token = iam.post()
                .uri(tokenUri)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (token == null || !(token.get("access_token") instanceof String accessToken)
                || accessToken.isBlank()) {
            throw new InvalidAdapterResponseException("IAM token response did not contain an access token");
        }
        AdapterSyncResult result = adapter.post()
                .uri(UPSERT_PATH)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AdapterSyncResult.class);
        if (result == null) {
            throw new InvalidAdapterResponseException("adapter response was empty");
        }
        return result;
    }

    private void verifyResponse(OpenMetadataSyncRequest request, AdapterSyncResult response) {
        boolean valid = request.datasetId().equals(response.datasetId())
                && request.versionId().equals(response.versionId())
                && "OPENMETADATA".equals(response.externalSystem())
                && "TABLE".equals(response.resourceType())
                && response.externalId() != null
                && request.openMetadataTableFqn().equals(response.externalFqn())
                && response.externalVersion() != null && !response.externalVersion().isBlank()
                && "SYNCED".equals(response.status())
                && response.syncedAt() != null;
        if (!valid) {
            throw new InvalidAdapterResponseException("adapter response did not match the requested mapping");
        }
    }

    private void markSucceeded(UUID eventId, int attempt, AdapterSyncResult response, OffsetDateTime now) {
        int mapped = jdbc.sql("""
                UPDATE integration.external_resource_mapping
                   SET external_id = :externalId,
                       external_fqn = :externalFqn,
                       external_version = :externalVersion,
                       sync_status = 'SYNCED',
                       last_synced_at = :syncedAt,
                       updated_at = :updatedAt
                 WHERE source_event_id = :eventId
                """)
                .param("externalId", response.externalId().toString())
                .param("externalFqn", response.externalFqn())
                .param("externalVersion", response.externalVersion())
                .param("syncedAt", response.syncedAt())
                .param("updatedAt", now)
                .param("eventId", eventId)
                .update();
        if (mapped != 1) {
            throw new IllegalStateException("OpenMetadata mapping intent is missing");
        }
        jdbc.sql("""
                UPDATE integration.outbox_event
                   SET published_at = :now,
                       publish_attempts = :attempt,
                       next_attempt_at = NULL,
                       failed_at = NULL,
                       last_error_code = NULL,
                       last_error_message = NULL
                 WHERE event_id = :eventId
                """)
                .param("now", now)
                .param("attempt", attempt)
                .param("eventId", eventId)
                .update();
        recordAttempt(eventId, attempt, "SUCCEEDED", null, null, now, null);
    }

    private void markFailed(UUID eventId,
                            int attempt,
                            DeliveryFailure failure,
                            boolean terminal,
                            OffsetDateTime now) {
        OffsetDateTime nextAttempt = terminal ? null : now.plus(backoff(attempt));
        jdbc.sql("""
                UPDATE integration.outbox_event
                   SET publish_attempts = :attempt,
                       next_attempt_at = :nextAttempt,
                       failed_at = :failedAt,
                       last_error_code = :errorCode,
                       last_error_message = :errorMessage
                 WHERE event_id = :eventId
                """)
                .param("attempt", attempt)
                .param("nextAttempt", nextAttempt)
                .param("failedAt", terminal ? now : null)
                .param("errorCode", failure.code())
                .param("errorMessage", failure.message())
                .param("eventId", eventId)
                .update();
        if (terminal) {
            jdbc.sql("""
                    UPDATE integration.external_resource_mapping
                       SET sync_status = 'FAILED', updated_at = :updatedAt
                     WHERE source_event_id = :eventId
                    """)
                    .param("updatedAt", now)
                    .param("eventId", eventId)
                    .update();
        }
        recordAttempt(eventId, attempt, terminal ? "TERMINAL_FAILED" : "RETRY_SCHEDULED",
                failure.code(), failure.message(), now, nextAttempt);
    }

    private void recordAttempt(UUID eventId,
                               int attempt,
                               String outcome,
                               String errorCode,
                               String errorMessage,
                               OffsetDateTime attemptedAt,
                               OffsetDateTime nextAttemptAt) {
        jdbc.sql("""
                INSERT INTO integration.outbox_delivery_attempt
                    (attempt_id, event_id, attempt_no, outcome, error_code, error_message,
                     attempted_at, next_attempt_at)
                VALUES (:attemptId, :eventId, :attemptNo, :outcome, :errorCode, :errorMessage,
                        :attemptedAt, :nextAttemptAt)
                """)
                .param("attemptId", UUID.randomUUID())
                .param("eventId", eventId)
                .param("attemptNo", attempt)
                .param("outcome", outcome)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("attemptedAt", attemptedAt)
                .param("nextAttemptAt", nextAttemptAt)
                .update();
    }

    private DeliveryFailure classify(Exception exception) {
        if (exception instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            return new DeliveryFailure("HTTP_" + status,
                    "remote service returned HTTP " + status, retryable);
        }
        if (exception instanceof JsonProcessingException) {
            return new DeliveryFailure("INVALID_EVENT_PAYLOAD", "stored event payload is invalid", false);
        }
        if (exception instanceof InvalidAdapterResponseException) {
            return new DeliveryFailure("INVALID_REMOTE_RESPONSE", sanitize(exception.getMessage()), true);
        }
        return new DeliveryFailure("REMOTE_CALL_FAILED", "remote service call failed", true);
    }

    private Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        Duration candidate = retryBaseDelay.multipliedBy(1L << exponent);
        return candidate.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : candidate;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String sanitize(String message) {
        String sanitized = message == null ? "invalid remote response" : message.replaceAll("[\\r\\n]+", " ");
        return sanitized.substring(0, Math.min(sanitized.length(), 500));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void requireHttpUri(String name, String value) {
        requireText(name, value);
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URI");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required when OpenMetadata sync is enabled");
        }
    }

    private record OutboxRow(UUID eventId, String payload, int attempts) {
    }

    private record DeliveryFailure(String code, String message, boolean retryable) {
    }

    private record AdapterSyncResult(
            UUID datasetId,
            UUID versionId,
            String externalSystem,
            String resourceType,
            UUID externalId,
            String externalFqn,
            String externalVersion,
            String status,
            OffsetDateTime syncedAt) {
    }

    private static final class InvalidAdapterResponseException extends RuntimeException {
        private InvalidAdapterResponseException(String message) {
            super(message);
        }
    }
}
