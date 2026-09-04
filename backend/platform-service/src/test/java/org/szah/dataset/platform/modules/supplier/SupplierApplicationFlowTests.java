package org.szah.dataset.platform.modules.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierApplicationFlowTests {
    private static final String APPLICATION = """
            {
              "organization_name": "合成肿瘤数据研究中心",
              "unified_social_credit_code": "91310000SYNTH00001",
              "contact_name": "测试联系人",
              "contact_phone": "13800000000"
            }
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SupplierApplicationService service;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void supplierOnboardingPersistsWorkflowAuditOutboxAndIdempotency() throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant("applicant-1"))
                        .header("Idempotency-Key", "create-supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn());
        String id = created.at("/data/id").asText();
        insertQualificationMaterial(java.util.UUID.fromString(id), "applicant-1");

        mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant("applicant-1"))
                        .header("Idempotency-Key", "create-supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id));

        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", id)
                        .with(applicant("applicant-1"))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "submit-supplier-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data.process_instance_id").isNotEmpty());

        mvc.perform(post("/api/v1/supplier-applications/{id}:return", id)
                        .with(operator("operator-1"))
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "return-supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"请补充许可证材料\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.data.status").value("RETURNED"));

        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", id)
                        .with(applicant("applicant-1"))
                        .header("If-Match", "\"3\"")
                        .header("Idempotency-Key", "resubmit-supplier-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"));

        mvc.perform(post("/api/v1/supplier-applications/{id}:approve", id)
                        .with(operator("operator-1"))
                        .header("If-Match", "\"5\"")
                        .header("Idempotency-Key", "approve-supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"材料核验通过\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mvc.perform(get("/api/v1/supplier-applications/{id}", id)
                        .with(applicant("applicant-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        java.util.UUID applicationId = java.util.UUID.fromString(id);
        assertThat(countBy("business.supplier", "application_id", applicationId)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM business.supplier_member sm
                        JOIN business.supplier s ON s.id = sm.supplier_id
                        WHERE s.application_id = ?
                        """).param(applicationId).query(Long.class).single()).isEqualTo(1);
        assertThat(countBy("business.supplier_application_state_history", "application_id", applicationId)).isEqualTo(7);
        assertThat(countBy("audit.audit_event", "object_id", applicationId)).isEqualTo(7);
        assertThat(countBy("integration.outbox_event", "aggregate_id", applicationId)).isEqualTo(8);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM business.supplier_application
                         WHERE id = ? AND submitted_snapshot = organization_snapshot
                        """).param(applicationId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT identity_sync_status FROM business.supplier WHERE application_id = ?")
                        .param(applicationId).query(String.class).single())
                .isEqualTo("PENDING");
        String outboxPayloads = jdbc.sql("""
                        SELECT payload FROM integration.outbox_event WHERE aggregate_id = ?
                        """).param(applicationId).query(String.class).list().toString();
        assertThat(outboxPayloads)
                .doesNotContain("测试联系人", "13800000000", "91310000SYNTH00001", "process_instance_id");
    }

    @Test
    void rejectsChangedPayloadForSameIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant("applicant-2"))
                        .header("Idempotency-Key", "reused-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant("applicant-2"))
                        .header("Idempotency-Key", "reused-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION.replace("测试联系人", "另一个联系人")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void serializesConcurrentRequestsWithSameIdempotencyKey() throws Exception {
        var command = new SupplierApplicationController.CreateSupplierApplication(
                "并发幂等测试供应商", "91310000LOCKTEST01", "并发联系人", "13700000000");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<SupplierApplicationView>) () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return service.create(command, "concurrent-applicant", "concurrent-create-key",
                        "concurrent-" + java.util.UUID.randomUUID());
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).id());
        }

        assertThat(jdbc.sql("SELECT COUNT(*) FROM business.supplier_application WHERE applicant_id = ?")
                .param("concurrent-applicant").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void enforcesAuthenticationRoleOwnershipAndVersion() throws Exception {
        mvc.perform(post("/api/v1/supplier-applications")
                        .header("Idempotency-Key", "anonymous-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/supplier-applications")
                        .with(jwt().jwt(token -> token.subject("platform-service-client")))
                        .header("Idempotency-Key", "service-account-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isForbidden());

        String id = body(mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant("applicant-3"))
                        .header("Idempotency-Key", "create-supplier-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated())
                .andReturn()).at("/data/id").asText();

        mvc.perform(get("/api/v1/supplier-applications/{id}", id)
                        .with(applicant("another-user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OBJECT_ACCESS_DENIED"));

        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", id)
                        .with(applicant("applicant-3"))
                        .header("If-Match", "\"9\"")
                        .header("Idempotency-Key", "stale-submit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURRENT_VERSION_CONFLICT"));

        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", id)
                        .with(applicant("applicant-3"))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "missing-material-submit"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUALIFICATION_MATERIAL_REQUIRED"));

        mvc.perform(post("/api/v1/supplier-applications/{id}:approve", id)
                        .with(applicant("applicant-3"))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "unauthorized-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/supplier-applications/{id}:approve", id)
                        .with(jwt().jwt(token -> token.subject("admin-1")
                                        .claim("preferred_username", "admin-1")
                                        .claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "admin-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"不应允许管理员审批\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/supplier-applications/{id}:withdraw", id)
                        .with(applicant("applicant-3"))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "withdraw-supplier-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
    }

    @Test
    void ownerUpdatesDraftAndReturnedApplicationWithoutOverwritingFirstSubmittedSnapshot() throws Exception {
        String actor = "update-applicant";
        String id = body(mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant(actor))
                        .header("Idempotency-Key", "update-flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated())
                .andReturn()).at("/data/id").asText();
        java.util.UUID applicationId = java.util.UUID.fromString(id);

        String firstUpdate = APPLICATION
                .replace("合成肿瘤数据研究中心", "合成肿瘤数据研究中心（修订）")
                .replace("测试联系人", "草稿联系人")
                .replace("13800000000", "13800000001");
        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "update-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUpdate))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.contact_name").value("草稿联系人"));

        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "update-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUpdate))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.contact_name").value("草稿联系人"));

        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "update-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUpdate.replace("草稿联系人", "冲突联系人")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "stale-update-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUpdate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURRENT_VERSION_CONFLICT"));

        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant("another-update-applicant"))
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "non-owner-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUpdate))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OBJECT_ACCESS_DENIED"));

        insertQualificationMaterial(applicationId, actor);

        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "update-flow-submit"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));

        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"3\"")
                        .header("Idempotency-Key", "update-under-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUpdate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mvc.perform(post("/api/v1/supplier-applications/{id}:return", id)
                        .with(operator("update-operator"))
                        .header("If-Match", "\"3\"")
                        .header("Idempotency-Key", "update-flow-return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"请修订联系人信息\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""));

        String returnedUpdate = firstUpdate
                .replace("草稿联系人", "退回后联系人")
                .replace("13800000001", "13800000002");
        mvc.perform(put("/api/v1/supplier-applications/{id}", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"4\"")
                        .header("Idempotency-Key", "update-returned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnedUpdate))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.contact_name").value("退回后联系人"))
                .andExpect(jsonPath("$.data.review_comment").value("请修订联系人信息"));

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM business.supplier_application
                         WHERE id = ?
                           AND submitted_snapshot <> organization_snapshot
                           AND submitted_snapshot LIKE '%草稿联系人%'
                           AND organization_snapshot LIKE '%退回后联系人%'
                           AND review_comment = '请修订联系人信息'
                        """).param(applicationId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM business.supplier_application_state_history
                         WHERE application_id = ? AND from_status = 'RETURNED' AND to_status = 'RETURNED'
                           AND business_version = 5
                        """).param(applicationId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM business.supplier_application_state_history
                         WHERE application_id = ? AND reason = '请修订联系人信息'
                        """).param(applicationId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM audit.audit_event
                         WHERE object_id = ? AND action = 'supplier.application.updated.v1'
                        """).param(applicationId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM integration.outbox_event
                         WHERE aggregate_id = ? AND event_type = 'supplier.application.updated.v1'
                        """).param(applicationId).query(Long.class).single()).isEqualTo(2);
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor operator(
            String subject) {
        return jwt().jwt(token -> token.subject(subject)
                        .claim("preferred_username", subject)
                        .claim("roles", List.of("OPERATOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor applicant(
            String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", subject));
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long countBy(String table, String column, java.util.UUID value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")
                .param(value).query(Long.class).single();
    }

    private void insertQualificationMaterial(java.util.UUID applicationId, String actorId) {
        java.util.UUID materialId = java.util.UUID.randomUUID();
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
