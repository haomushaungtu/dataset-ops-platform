package org.szah.dataset.platform.modules.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.szah.dataset.platform.storage.QualificationMaterialStorage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SupplierQualificationMaterialTests.StorageTestConfiguration.class)
class SupplierQualificationMaterialTests {
    private static final byte[] PDF = "%PDF-1.7\nsynthetic qualification".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
    private static final String APPLICATION = """
            {
              "organization_name": "合成资质材料测试机构",
              "unified_social_credit_code": "91310000MATERIAL01",
              "contact_name": "材料联系人",
              "contact_phone": "13800000009"
            }
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InMemoryQualificationMaterialStorage storage;

    @MockBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetStorage() {
        storage.reset();
    }

    @Test
    void uploadsVersionedMaterialsWithHashSafeKeyAndIdempotentReplay() throws Exception {
        String actor = "material-owner";
        String id = create(actor, "material-create-1");
        UUID applicationId = UUID.fromString(id);

        JsonNode first = body(mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                                "..\\private\\license.pdf", "application/pdf", PDF,
                                "\"0\"", "material-upload-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.application_version").value(1))
                .andExpect(jsonPath("$.data.material.version_no").value(1))
                .andExpect(jsonPath("$.data.material.original_file_name").value("license.pdf"))
                .andExpect(jsonPath("$.data.material.media_type").value("application/pdf"))
                .andExpect(jsonPath("$.data.material.sha256").value(sha256(PDF)))
                .andReturn());
        String firstMaterialId = first.at("/data/material/id").asText();

        mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                        "..\\private\\license.pdf", "application/pdf", PDF,
                        "\"0\"", "material-upload-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.material.id").value(firstMaterialId));

        mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                        "license-v2.png", "image/png", PNG,
                        "\"1\"", "material-upload-2"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.material.version_no").value(2))
                .andExpect(jsonPath("$.data.material.media_type").value("image/png"));

        mvc.perform(materialUpload(id, actor, "REPRESENTATIVE_PHOTO",
                        "representative.jpg", "image/jpeg", JPEG,
                        "\"2\"", "material-upload-3"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.data.material.version_no").value(1))
                .andExpect(jsonPath("$.data.material.media_type").value("image/jpeg"));

        String ownerList = mvc.perform(get("/api/v1/supplier-applications/{id}/materials", id)
                        .with(applicant(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(ownerList).doesNotContain("object_key", "storage_bucket", "access_key", "secret_key");

        mvc.perform(get("/api/v1/supplier-applications/{id}/materials", id)
                        .with(operator("material-operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get("/api/v1/supplier-applications/{id}/materials", id)
                        .with(applicant("material-outsider")))
                .andExpect(status().isForbidden());

        assertThat(storage.storeCalls.get()).isEqualTo(3);
        assertThat(storage.objects).hasSize(3);
        assertThat(storage.objects.keySet()).allSatisfy(key -> {
            assertThat(key).matches("test-prefix/supplier-applications/[0-9a-f-]+/qualification-materials/[0-9a-f-]+");
            assertThat(key).doesNotContain("license", "representative", "..", "\\");
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM business.supplier_qualification_material WHERE application_id = ?")
                .param(applicationId).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT version FROM business.supplier_application WHERE id = ?")
                .param(applicationId).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM business.supplier_application_state_history
                         WHERE application_id = ? AND from_status = 'DRAFT' AND to_status = 'DRAFT'
                           AND reason LIKE 'material:%'
                        """).param(applicationId).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM audit.audit_event
                         WHERE object_id = ? AND action = 'supplier.qualification-material.uploaded.v1'
                        """).param(applicationId).query(Long.class).single()).isEqualTo(3);
        String outbox = jdbc.sql("""
                        SELECT payload FROM integration.outbox_event
                         WHERE aggregate_id = ? AND event_type = 'supplier.qualification-material.uploaded.v1'
                        """).param(applicationId).query(String.class).list().toString();
        assertThat(outbox).doesNotContain("license.pdf", "representative.jpg", "test-bucket", "test-prefix", "材料联系人");
    }

    @Test
    void rejectsNonOwnerInvalidTypeOversizeAndNonEditableState() throws Exception {
        String actor = "material-validation-owner";
        String id = create(actor, "material-create-2");

        mvc.perform(materialUpload(id, "material-outsider", "BUSINESS_LICENSE",
                        "license.pdf", "application/pdf", PDF, "\"0\"", "outsider-upload"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OBJECT_ACCESS_DENIED"));

        mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                        "not-a-pdf.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8),
                        "\"0\"", "invalid-type"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MATERIAL_FILE_TYPE_NOT_ALLOWED"));

        byte[] oversized = new byte[(int) SupplierQualificationMaterialService.MAX_SIZE + 1];
        System.arraycopy(PDF, 0, oversized, 0, PDF.length);
        mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                        "large.pdf", "application/pdf", oversized, "\"0\"", "oversized"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("MATERIAL_FILE_TOO_LARGE"));

        insertQualificationMaterial(java.util.UUID.fromString(id), actor);
        mvc.perform(post("/api/v1/supplier-applications/{id}:submit", id)
                        .with(applicant(actor))
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "material-submit"))
                .andExpect(status().isOk());
        mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                        "license.pdf", "application/pdf", PDF, "\"2\"", "under-review-upload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertThat(storage.storeCalls.get()).isZero();
    }

    @Test
    void compensatesStableObjectWhenDatabaseWriteFails() throws Exception {
        String actor = "material-compensation-owner";
        String id = create(actor, "material-create-3");
        UUID applicationId = UUID.fromString(id);
        storage.afterStore = () -> jdbc.sql("""
                UPDATE business.supplier_application SET version = version + 1 WHERE id = ?
                """).param(applicationId).update();

        mvc.perform(materialUpload(id, actor, "BUSINESS_LICENSE",
                        "license.pdf", "application/pdf", PDF, "\"0\"", "compensation-upload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        assertThat(storage.storeCalls.get()).isEqualTo(1);
        assertThat(storage.deleteCalls.get()).isEqualTo(1);
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.sql("SELECT version FROM business.supplier_application WHERE id = ?")
                .param(applicationId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM business.supplier_qualification_material WHERE application_id = ?")
                .param(applicationId).query(Long.class).single()).isZero();
    }

    private String create(String actor, String key) throws Exception {
        return body(mvc.perform(post("/api/v1/supplier-applications")
                        .with(applicant(actor))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION.replace("MATERIAL01", key.endsWith("1") ? "MATERIAL01"
                                : key.endsWith("2") ? "MATERIAL02" : "MATERIAL03")))
                .andExpect(status().isCreated())
                .andReturn()).at("/data/id").asText();
    }

    private org.springframework.test.web.servlet.RequestBuilder materialUpload(
            String id,
            String actor,
            String materialType,
            String fileName,
            String contentType,
            byte[] content,
            String ifMatch,
            String key) {
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", fileName, contentType, content);
        return multipart("/api/v1/supplier-applications/{id}/materials", id)
                .file(file)
                .param("material_type", materialType)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", key)
                .with(applicant(actor));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor applicant(
            String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", subject));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor operator(
            String subject) {
        return jwt().jwt(token -> token.subject(subject)
                        .claim("preferred_username", subject)
                        .claim("roles", List.of("OPERATOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean
        @Primary
        InMemoryQualificationMaterialStorage qualificationMaterialStorage() {
            return new InMemoryQualificationMaterialStorage();
        }
    }

    static final class InMemoryQualificationMaterialStorage implements QualificationMaterialStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger storeCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        volatile Runnable afterStore = () -> { };

        @Override
        public StoredObject store(UUID applicationId,
                                  UUID materialId,
                                  Path source,
                                  long size,
                                  String mediaType,
                                  String sha256) {
            try {
                byte[] content = Files.readAllBytes(source);
                assertThat(content).hasSize((int) size);
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))
                        .isEqualTo(sha256);
                String key = "test-prefix/supplier-applications/" + applicationId
                        + "/qualification-materials/" + materialId;
                objects.put(key, Arrays.copyOf(content, content.length));
                storeCalls.incrementAndGet();
                Runnable callback = afterStore;
                afterStore = () -> { };
                callback.run();
                return new StoredObject("test-bucket", key, sha256, null);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void delete(StoredObject object) {
            objects.remove(object.objectKey());
            deleteCalls.incrementAndGet();
        }

        void reset() {
            objects.clear();
            storeCalls.set(0);
            deleteCalls.set(0);
            afterStore = () -> { };
        }
    }
}
