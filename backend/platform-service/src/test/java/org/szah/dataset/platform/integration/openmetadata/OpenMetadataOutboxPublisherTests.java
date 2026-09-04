package org.szah.dataset.platform.integration.openmetadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenMetadataOutboxPublisherTests {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    Validator validator;
    @Autowired
    RestClient.Builder restClient;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    OpenMetadataSyncOutboxService outbox;
    @Autowired
    OpenMetadataDeliveryQueryService queries;
    @Autowired
    MockMvc mvc;

    @MockBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void clearOpenMetadataDeliveryState() {
        jdbc.sql("""
                DELETE FROM integration.outbox_delivery_attempt
                 WHERE event_id IN (
                    SELECT event_id FROM integration.outbox_event WHERE event_type = :eventType
                 )
                """)
                .param("eventType", OpenMetadataSyncOutboxService.EVENT_TYPE)
                .update();
        jdbc.sql("DELETE FROM integration.external_resource_mapping WHERE external_system = 'OPENMETADATA'")
                .update();
        jdbc.sql("DELETE FROM integration.outbox_event WHERE event_type = :eventType")
                .param("eventType", OpenMetadataSyncOutboxService.EVENT_TYPE)
                .update();
    }

    @Test
    void outboxAndMappingRollBackWithCallingBusinessTransaction() {
        OpenMetadataSyncRequest request = request(UUID.randomUUID(), UUID.randomUUID());
        UUID[] eventId = new UUID[1];

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventId[0] = outbox.enqueue(request, 7, "rollback-request", "quality-service");
            status.setRollbackOnly();
        });

        assertThat(count("integration.outbox_event", "event_id", eventId[0])).isZero();
        assertThat(count("integration.external_resource_mapping", "source_event_id", eventId[0])).isZero();
    }

    @Test
    void duplicateEnqueueReturnsOriginalEventWithoutCreatingAnotherMapping() {
        OpenMetadataSyncRequest request = request(UUID.randomUUID(), UUID.randomUUID());

        UUID first = outbox.enqueue(request, 2, "duplicate-request", "quality-service");
        UUID replay = outbox.enqueue(request, 2, "duplicate-request", "quality-service");

        assertThat(replay).isEqualTo(first);
        assertThat(count("integration.outbox_event", "event_id", first)).isOne();
        assertThat(count("integration.external_resource_mapping", "source_event_id", first)).isOne();
    }

    @Test
    void publishesOnceAndPersistsVerifiedExternalMapping() throws Exception {
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID externalId = UUID.randomUUID();
        OpenMetadataSyncRequest request = request(datasetId, versionId);
        UUID eventId = outbox.enqueue(request, 4, "success-request", "quality-service");
        AtomicInteger deliveries = new AtomicInteger();

        HttpServer server = server(exchange -> {
            deliveries.incrementAndGet();
            respond(exchange, 200, "application/json", """
                    {"dataset_id":"%s","version_id":"%s","external_system":"OPENMETADATA",
                     "resource_type":"TABLE","external_id":"%s","external_fqn":"%s",
                     "external_version":"0.2","status":"SYNCED","synced_at":"2026-09-04T02:30:00Z"}
                    """.formatted(datasetId, versionId, externalId, request.openMetadataTableFqn()));
        });
        try {
            publisher(server, 3).publish();
            publisher(server, 3).publish();
        } finally {
            server.stop(0);
        }

        OpenMetadataDeliveryView delivery = queries.get(eventId);
        assertThat(deliveries).hasValue(1);
        assertThat(delivery.syncStatus()).isEqualTo("SYNCED");
        assertThat(delivery.externalId()).isEqualTo(externalId.toString());
        assertThat(delivery.externalFqn()).isEqualTo(request.openMetadataTableFqn());
        assertThat(delivery.publishedAt()).isNotNull();
        assertThat(delivery.failedAt()).isNull();
        assertThat(delivery.attemptHistory()).extracting(OpenMetadataDeliveryView.Attempt::outcome)
                .containsExactly("SUCCEEDED");
    }

    @Test
    void retriesTransientFailureThenStopsAtConfiguredBoundWithAuditableHistory() throws Exception {
        OpenMetadataSyncRequest request = request(UUID.randomUUID(), UUID.randomUUID());
        UUID eventId = outbox.enqueue(request, 1, "failure-request", "quality-service");
        AtomicInteger deliveries = new AtomicInteger();
        HttpServer server = server(exchange -> {
            deliveries.incrementAndGet();
            respond(exchange, 503, "application/problem+json", "{\"detail\":\"synthetic unavailable\"}");
        });
        try {
            OpenMetadataOutboxPublisher publisher = publisher(server, 2);
            publisher.publish();
            jdbc.sql("UPDATE integration.outbox_event SET next_attempt_at = :nextAttempt "
                            + "WHERE event_id = :eventId")
                    .param("nextAttempt", java.time.OffsetDateTime.parse("2026-09-04T02:59:59Z"))
                    .param("eventId", eventId)
                    .update();
            publisher.publish();
            publisher.publish();
        } finally {
            server.stop(0);
        }

        OpenMetadataDeliveryView delivery = queries.get(eventId);
        assertThat(deliveries).hasValue(2);
        assertThat(delivery.syncStatus()).isEqualTo("FAILED");
        assertThat(delivery.attempts()).isEqualTo(2);
        assertThat(delivery.failedAt()).isNotNull();
        assertThat(delivery.nextAttemptAt()).isNull();
        assertThat(delivery.lastErrorCode()).isEqualTo("HTTP_503");
        assertThat(delivery.lastErrorMessage()).doesNotContain("synthetic unavailable");
        assertThat(delivery.attemptHistory()).extracting(OpenMetadataDeliveryView.Attempt::outcome)
                .containsExactly("RETRY_SCHEDULED", "TERMINAL_FAILED");
        assertThat(queries.terminalFailures(20)).extracting(OpenMetadataDeliveryView::eventId)
                .contains(eventId);
    }

    @Test
    void deterministicAdapterConflictFailsImmediatelyWithoutRetry() throws Exception {
        OpenMetadataSyncRequest request = request(UUID.randomUUID(), UUID.randomUUID());
        UUID eventId = outbox.enqueue(request, 1, "conflict-request", "quality-service");
        AtomicInteger deliveries = new AtomicInteger();
        HttpServer server = server(exchange -> {
            deliveries.incrementAndGet();
            respond(exchange, 409, "application/problem+json", "{\"detail\":\"mapping conflict\"}");
        });
        try {
            OpenMetadataOutboxPublisher publisher = publisher(server, 6);
            publisher.publish();
            publisher.publish();
        } finally {
            server.stop(0);
        }

        OpenMetadataDeliveryView delivery = queries.get(eventId);
        assertThat(deliveries).hasValue(1);
        assertThat(delivery.failedAt()).isNotNull();
        assertThat(delivery.attempts()).isOne();
        assertThat(delivery.lastErrorCode()).isEqualTo("HTTP_409");
        assertThat(delivery.attemptHistory()).extracting(OpenMetadataDeliveryView.Attempt::outcome)
                .containsExactly("TERMINAL_FAILED");
    }

    @Test
    void failureQueryRequiresOperatorOrAdminAndReturnsAuditHistory() throws Exception {
        OpenMetadataSyncRequest request = request(UUID.randomUUID(), UUID.randomUUID());
        UUID eventId = outbox.enqueue(request, 1, "query-request", "quality-service");
        jdbc.sql("""
                UPDATE integration.outbox_event
                   SET publish_attempts = 1, failed_at = CURRENT_TIMESTAMP,
                       last_error_code = 'HTTP_409', last_error_message = 'remote service returned HTTP 409'
                 WHERE event_id = :eventId
                """).param("eventId", eventId).update();
        jdbc.sql("""
                UPDATE integration.external_resource_mapping
                   SET sync_status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                 WHERE source_event_id = :eventId
                """).param("eventId", eventId).update();
        jdbc.sql("""
                INSERT INTO integration.outbox_delivery_attempt
                    (attempt_id, event_id, attempt_no, outcome, error_code, error_message, attempted_at)
                VALUES (:attemptId, :eventId, 1, 'TERMINAL_FAILED', 'HTTP_409',
                        'remote service returned HTTP 409', CURRENT_TIMESTAMP)
                """)
                .param("attemptId", UUID.randomUUID())
                .param("eventId", eventId)
                .update();

        mvc.perform(get("/api/v1/integrations/openmetadata/deliveries/failed"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/integrations/openmetadata/deliveries/failed")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPLIER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/integrations/openmetadata/deliveries/failed")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_id").value(eventId.toString()))
                .andExpect(jsonPath("$[0].sync_status").value("FAILED"))
                .andExpect(jsonPath("$[0].attempt_history[0].outcome").value("TERMINAL_FAILED"));
    }

    private OpenMetadataOutboxPublisher publisher(HttpServer server, int maxAttempts) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OpenMetadataOutboxPublisher(
                jdbc, objectMapper, restClient, transactionManager, baseUrl, baseUrl + "/oauth2/token",
                "synthetic-client", "synthetic-secret", "platform.internal", maxAttempts,
                Duration.ofSeconds(1), Duration.ofSeconds(8),
                Clock.fixed(Instant.parse("2026-09-04T03:00:00Z"), ZoneOffset.UTC));
    }

    private static HttpServer server(ExchangeHandler adapterHandler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> respond(
                exchange, 200, "application/json", "{\"access_token\":\"synthetic-service-token\"}"));
        server.createContext("/api/v1/openmetadata/dataset-versions:upsert", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer synthetic-service-token");
            adapterHandler.handle(exchange);
        });
        server.start();
        return server;
    }

    private static OpenMetadataSyncRequest request(UUID datasetId, UUID versionId) {
        return new OpenMetadataSyncRequest(
                datasetId,
                versionId,
                "phase1.synthetic." + versionId.toString().replace("-", ""),
                List.of("肺癌"),
                List.of("STRUCTURED"),
                new OpenMetadataSyncRequest.QualitySummary(
                        new BigDecimal("92.50"), "A", OpenMetadataSyncRequest.GateResult.PASS));
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value")
                .param("value", value)
                .query(Long.class)
                .single();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body == null ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
