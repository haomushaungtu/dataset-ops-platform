package org.szah.dataset.integrations.openmetadata.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.szah.dataset.integrations.openmetadata.security.SecurityConfiguration;
import org.szah.dataset.integrations.openmetadata.sync.MetadataSyncResult;
import org.szah.dataset.integrations.openmetadata.sync.OpenMetadataGateway;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MetadataSyncController.class,
        properties = {
                "adapter.security.issuer=http://iam.test",
                "adapter.security.audience=openmetadata-adapter-api",
                "adapter.iam.token-uri=http://iam.test/oauth2/token",
                "adapter.iam.client-id=synthetic-adapter",
                "adapter.iam.client-secret=synthetic-test-secret",
                "adapter.iam.scope=platform.internal",
                "adapter.openmetadata.base-url=http://openmetadata.test/api",
                "adapter.openmetadata.connect-timeout=PT1S",
                "adapter.openmetadata.read-timeout=PT2S"
        })
@Import(SecurityConfiguration.class)
class MetadataSyncSecurityTests {
    private static final UUID DATASET_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TABLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String REQUEST = """
            {
              "dataset_id": "30000000-0000-0000-0000-000000000001",
              "version_id": "40000000-0000-0000-0000-000000000001",
              "open_metadata_table_fqn": "poc.synthetic.cancer_registry",
              "cancer_types": ["肺癌"],
              "modalities": ["STRUCTURED"],
              "quality_summary": {"score": 92.50, "grade": "A", "gate_result": "PASS"}
            }
            """;

    @Autowired
    MockMvc mvc;

    @MockBean
    JwtDecoder jwtDecoder;

    @MockBean
    OpenMetadataGateway gateway;

    @BeforeEach
    void configureMocks() {
        reset(jwtDecoder, gateway);
        when(jwtDecoder.decode("wrong-scope-token"))
                .thenReturn(jwt("wrong-scope-token", "metadata.read"));
        when(jwtDecoder.decode("valid-machine-token"))
                .thenReturn(jwt("valid-machine-token", "platform.internal"));
        when(gateway.upsertDatasetVersion(argThat(command -> command != null
                && DATASET_ID.equals(command.datasetId())
                && VERSION_ID.equals(command.versionId()))))
                .thenReturn(new MetadataSyncResult(
                        DATASET_ID,
                        VERSION_ID,
                        "OPENMETADATA",
                        "TABLE",
                        TABLE_ID,
                        "poc.synthetic.cancer_registry",
                        "0.2",
                        MetadataSyncResult.SyncStatus.SYNCED,
                        OffsetDateTime.of(2026, 9, 4, 2, 30, 0, 0, ZoneOffset.UTC)));
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(post("/api/v1/openmetadata/dataset-versions:upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized());

        verify(gateway, never()).upsertDatasetVersion(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMachineTokenWithoutPlatformInternalScope() throws Exception {
        mvc.perform(post("/api/v1/openmetadata/dataset-versions:upsert")
                        .header("Authorization", "Bearer wrong-scope-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());

        verify(gateway, never()).upsertDatasetVersion(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsCorrectMachineTokenOnceAndDoesNotExposeCredentials() throws Exception {
        mvc.perform(post("/api/v1/openmetadata/dataset-versions:upsert")
                        .header("Authorization", "Bearer valid-machine-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SYNCED"))
                .andExpect(jsonPath("$.external_id").value(TABLE_ID.toString()))
                .andExpect(content().string(not(containsString("valid-machine-token"))))
                .andExpect(content().string(not(containsString("synthetic-test-secret"))))
                .andExpect(content().string(not(containsString("access_token"))))
                .andExpect(content().string(not(containsString("client_secret"))));

        verify(gateway, times(1)).upsertDatasetVersion(argThat(command ->
                DATASET_ID.equals(command.datasetId()) && VERSION_ID.equals(command.versionId())));
        verify(jwtDecoder, times(1)).decode("valid-machine-token");
    }

    private static Jwt jwt(String tokenValue, String scope) {
        Instant now = Instant.parse("2026-09-04T02:00:00Z");
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .issuer("http://iam.test")
                .audience(List.of("openmetadata-adapter-api"))
                .subject("openmetadata-adapter-client")
                .claim("scope", scope)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
