package org.szah.dataset.platform.modules.supplier;

import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("postgres-it")
@EnabledIfEnvironmentVariable(named = "DF_PLATFORM_POSTGRES_IT", matches = "true")
class PostgreSqlSupplierIntegrationTests {
    @Autowired
    SupplierApplicationService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RepositoryService repositoryService;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    @Transactional
    void migratesSchemasAndRunsSupplierWorkflowInPostgreSql() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single())
                .isEqualTo("platform_ops_poc");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM information_schema.schemata
                         WHERE schema_name IN ('business','audit','integration','workflow')
                        """).query(Long.class).single()).isEqualTo(4);
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("supplierOnboarding").count()).isGreaterThan(0);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SupplierApplicationView created = service.create(
                new SupplierApplicationController.CreateSupplierApplication(
                        "PostgreSQL 合成供应商 " + suffix,
                        "91" + suffix + "00000000",
                        "集成测试联系人",
                        "13900000000"),
                "pg-it-applicant", "pg-create-" + suffix, "pg-request-create-" + suffix);
        insertQualificationMaterial(created.id(), created.applicantId());
        SupplierApplicationView submitted = service.submit(
                created.id(), created.applicantId(), created.version(),
                "pg-submit-" + suffix, "pg-request-submit-" + suffix);
        SupplierApplicationView approved = service.decide(
                created.id(), "pg-it-operator", SupplierApplicationStatus.APPROVED,
                "PostgreSQL 与 Flowable 集成验证通过", submitted.version(),
                "pg-approve-" + suffix, "pg-request-approve-" + suffix);

        assertThat(approved.status()).isEqualTo(SupplierApplicationStatus.APPROVED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM business.supplier WHERE application_id = ?")
                .param(created.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit.audit_event WHERE object_id = ?")
                .param(created.id()).query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM integration.outbox_event WHERE aggregate_id = ?")
                .param(created.id()).query(Long.class).single()).isEqualTo(5);
    }

    @Test
    void serializesConcurrentIdempotentCreateInPostgreSql() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String actor = "pg-concurrent-" + suffix;
        String key = "pg-concurrent-key-" + suffix;
        var command = new SupplierApplicationController.CreateSupplierApplication(
                "PostgreSQL 并发幂等供应商 " + suffix,
                "91" + suffix + "00000000",
                "并发集成测试联系人",
                "13600000000");
        UUID applicationId = null;
        try {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var task = (java.util.concurrent.Callable<SupplierApplicationView>) () -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.create(command, actor, key, "pg-concurrent-request-" + UUID.randomUUID());
                };
                var first = executor.submit(task);
                var second = executor.submit(task);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                SupplierApplicationView firstResult = first.get(15, TimeUnit.SECONDS);
                SupplierApplicationView secondResult = second.get(15, TimeUnit.SECONDS);
                assertThat(firstResult.id()).isEqualTo(secondResult.id());
                applicationId = firstResult.id();
            }

            assertThat(jdbc.sql("SELECT COUNT(*) FROM business.supplier_application WHERE applicant_id = ?")
                    .param(actor).query(Long.class).single()).isEqualTo(1);
        } finally {
            if (applicationId != null) {
                jdbc.sql("DELETE FROM integration.outbox_event WHERE aggregate_id = ?").param(applicationId).update();
                jdbc.sql("DELETE FROM audit.audit_event WHERE object_id = ?").param(applicationId).update();
                jdbc.sql("DELETE FROM business.supplier_application_state_history WHERE application_id = ?")
                        .param(applicationId).update();
                jdbc.sql("DELETE FROM business.supplier_application WHERE id = ?").param(applicationId).update();
            }
            jdbc.sql("DELETE FROM integration.idempotency_record WHERE actor_id = ?")
                    .param(actor).update();
            jdbc.sql("DELETE FROM integration.idempotency_lock WHERE actor_id = ?")
                    .param(actor).update();
        }
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
