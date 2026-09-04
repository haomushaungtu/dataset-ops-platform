package org.szah.dataset.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.szah.dataset.platform.modules.supplier.SupplierApplicationController;
import org.szah.dataset.platform.modules.supplier.SupplierApplicationService;
import org.szah.dataset.platform.modules.supplier.SupplierApplicationStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class IdentityRoleOutboxPublisherTests {
    @Autowired
    SupplierApplicationService supplierApplications;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    RestClient.Builder restClient;
    @Autowired
    PlatformTransactionManager transactionManager;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void grantsSupplierRoleAndCompletesOutboxEvent() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String applicantId = UUID.randomUUID().toString();
        var created = supplierApplications.create(
                new SupplierApplicationController.CreateSupplierApplication(
                        "身份同步测试供应商 " + suffix,
                        "91" + suffix + "00000000",
                        "身份同步联系人",
                        "13500000000"),
                applicantId, "identity-create-" + suffix, "identity-request-create-" + suffix);
        insertQualificationMaterial(created.id(), applicantId);
        var submitted = supplierApplications.submit(created.id(), applicantId, created.version(),
                "identity-submit-" + suffix, "identity-request-submit-" + suffix);
        var approved = supplierApplications.decide(created.id(), "operator-id",
                SupplierApplicationStatus.APPROVED, "身份同步测试通过", submitted.version(),
                "identity-approve-" + suffix, "identity-request-approve-" + suffix);

        AtomicInteger grants = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> respond(exchange, 200,
                "application/json", "{\"access_token\":\"synthetic-service-token\"}"));
        server.createContext("/api/v1/internal/subjects/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            assertThat(exchange.getRequestURI().getPath())
                    .isEqualTo("/api/v1/internal/subjects/" + applicantId + "/roles/supplier");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer synthetic-service-token");
            grants.incrementAndGet();
            respond(exchange, 204, null, null);
        });
        server.start();
        try {
            var publisher = new IdentityRoleOutboxPublisher(
                    jdbc, objectMapper, restClient, transactionManager,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "client", "secret");
            publisher.publish();
        } finally {
            server.stop(0);
        }

        assertThat(grants.get()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT identity_sync_status FROM business.supplier WHERE application_id = ?")
                .param(approved.id()).query(String.class).single()).isEqualTo("SYNCED");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM integration.outbox_event
                         WHERE aggregate_id = ?
                           AND event_type = 'supplier.identity-role.requested.v1'
                           AND published_at IS NOT NULL
                        """).param(approved.id()).query(Long.class).single()).isEqualTo(1);
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

    private void insertQualificationMaterial(UUID applicationId, String actorId) {
        UUID materialId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO business.supplier_qualification_material (
                    id, application_id, material_type, version_no, original_file_name, media_type,
                    size_bytes, sha256, storage_bucket, object_key, uploaded_by, uploaded_at
                ) VALUES (?, ?, 'BUSINESS_LICENSE', 1, 'synthetic.pdf', 'application/pdf',
                          1, ?, 'test-bucket', ?, ?, CURRENT_TIMESTAMP)
                """)
                .params(materialId, applicationId, "0".repeat(64), "tests/" + materialId, actorId)
                .update();
    }
}
