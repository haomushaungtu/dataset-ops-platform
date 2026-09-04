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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierApplicationHistoryTests {
    private static final String APPLICATION = """
            {
              "organization_name": "合成历史查询研究中心",
              "unified_social_credit_code": "91310000HISTORY001",
              "contact_name": "历史测试联系人",
              "contact_phone": "13800000009"
            }
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void returnsOrderedStateHistoryWithoutStorageDetails() throws Exception {
        String owner = "history-owner";
        String applicationId = create(owner);
        insertQualificationMaterial(applicationId, owner);

        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", applicationId)
                        .with(user(owner))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "history-submit")
                        .header("X-Request-Id", "history-submit-request"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/supplier-applications/{id}:return", applicationId)
                        .with(role("history-operator", "OPERATOR"))
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "history-return")
                        .header("X-Request-Id", "history-return-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"请补充资质材料\"}"))
                .andExpect(status().isOk());

        String response = mvc.perform(get("/api/v1/supplier-applications/{id}/history", applicationId)
                        .with(user(owner))
                        .header("X-Request-Id", "history-list-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("history-list-request"))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].from_status").doesNotExist())
                .andExpect(jsonPath("$.data[0].to_status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].actor_id").value(owner))
                .andExpect(jsonPath("$.data[0].business_version").value(0))
                .andExpect(jsonPath("$.data[1].from_status").value("DRAFT"))
                .andExpect(jsonPath("$.data[1].to_status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[1].business_version").value(1))
                .andExpect(jsonPath("$.data[1].request_id").value("history-submit-request"))
                .andExpect(jsonPath("$.data[2].from_status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[2].to_status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data[2].actor_id").value("workflow"))
                .andExpect(jsonPath("$.data[2].business_version").value(2))
                .andExpect(jsonPath("$.data[3].from_status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data[3].to_status").value("RETURNED"))
                .andExpect(jsonPath("$.data[3].actor_id").value("history-operator"))
                .andExpect(jsonPath("$.data[3].reason").value("请补充资质材料"))
                .andExpect(jsonPath("$.data[3].request_id").value("history-return-request"))
                .andExpect(jsonPath("$.data[3].business_version").value(3))
                .andExpect(jsonPath("$.data[3].occurred_at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(
                "object_key", "storage_bucket", "object_etag", "object_version_id",
                "access_key", "secret_key", "credential");
    }

    @Test
    void enforcesOwnerOperatorAdminAndNotFoundBoundaries() throws Exception {
        String applicationId = create("history-access-owner");

        mvc.perform(get("/api/v1/supplier-applications/{id}/history", applicationId)
                        .with(user("history-outsider")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OBJECT_ACCESS_DENIED"));

        mvc.perform(get("/api/v1/supplier-applications/{id}/history", applicationId)
                        .with(role("history-access-operator", "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(get("/api/v1/supplier-applications/{id}/history", applicationId)
                        .with(role("history-access-admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(get("/api/v1/supplier-applications/{id}/history", UUID.randomUUID())
                        .with(role("history-access-operator", "OPERATOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_APPLICATION_NOT_FOUND"));

        mvc.perform(get("/api/v1/supplier-applications/{id}/history", applicationId))
                .andExpect(status().isUnauthorized());
    }

    private String create(String owner) throws Exception {
        JsonNode response = objectMapper.readTree(mvc.perform(post("/api/v1/supplier-applications")
                        .with(user(owner))
                        .header("Idempotency-Key", "create-" + owner)
                        .header("X-Request-Id", "create-request-" + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return response.at("/data/id").asText();
    }

    private void insertQualificationMaterial(String applicationId, String owner) {
        UUID materialId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO business.supplier_qualification_material (
                    id, application_id, material_type, version_no, original_file_name, media_type,
                    size_bytes, sha256, storage_bucket, object_key, uploaded_by, uploaded_at
                ) VALUES (?, ?, 'BUSINESS_LICENSE', 1, 'synthetic-license.pdf', 'application/pdf',
                          4, ?, 'history-private-bucket', ?, ?, CURRENT_TIMESTAMP)
                """)
                .params(materialId, UUID.fromString(applicationId), "0".repeat(64),
                        "private/history/" + materialId, owner)
                .update();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor user(
            String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", subject));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor role(
            String subject, String role) {
        return jwt().jwt(token -> token.subject(subject)
                        .claim("preferred_username", subject)
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
