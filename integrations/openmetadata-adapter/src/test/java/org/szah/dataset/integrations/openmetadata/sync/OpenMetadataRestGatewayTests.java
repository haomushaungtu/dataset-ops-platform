package org.szah.dataset.integrations.openmetadata.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.szah.dataset.integrations.openmetadata.auth.ServiceTokenProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenMetadataRestGatewayTests {
    private static final UUID TABLE_TYPE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID STRING_TYPE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID TABLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DATASET_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Test
    void createsMissingDefinitionsPatchesAndVerifiesDatasetVersionMetadata() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://openmetadata.test/api");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenMetadataGateway gateway = gateway(builder);

        server.expect(once(), requestTo("http://openmetadata.test/api/v1/metadata/types/name/table?fields=customProperties"))
                .andExpect(bearer())
                .andRespond(json("{\"id\":\"" + TABLE_TYPE_ID + "\",\"customProperties\":[]}"));
        server.expect(once(), requestTo("http://openmetadata.test/api/v1/metadata/types/name/string"))
                .andExpect(bearer())
                .andRespond(json("{\"id\":\"" + STRING_TYPE_ID + "\"}"));
        server.expect(times(5), requestTo("http://openmetadata.test/api/v1/metadata/types/" + TABLE_TYPE_ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(bearer())
                .andRespond(json("{}"));
        server.expect(once(), requestTo("http://openmetadata.test/api/v1/metadata/types/name/table?fields=customProperties"))
                .andExpect(bearer())
                .andRespond(json(existingDefinitions()));
        server.expect(once(), requestTo(
                        "http://openmetadata.test/api/v1/tables/name/poc.synthetic.cancer_registry?fields=extension"))
                .andExpect(bearer())
                .andRespond(json("{\"id\":\"" + TABLE_ID
                        + "\",\"fullyQualifiedName\":\"poc.synthetic.cancer_registry\",\"version\":0.1}"));
        server.expect(once(), requestTo("http://openmetadata.test/api/v1/tables/" + TABLE_ID))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, "application/json-patch+json"))
                .andExpect(bearer())
                .andRespond(json("{}"));
        server.expect(once(), requestTo(
                        "http://openmetadata.test/api/v1/tables/" + TABLE_ID + "?fields=extension"))
                .andExpect(bearer())
                .andRespond(json(verifiedTable()));

        MetadataSyncResult result = gateway.upsertDatasetVersion(command(DATASET_ID));

        assertThat(result.status()).isEqualTo(MetadataSyncResult.SyncStatus.SYNCED);
        assertThat(result.datasetId()).isEqualTo(DATASET_ID);
        assertThat(result.versionId()).isEqualTo(VERSION_ID);
        assertThat(result.externalId()).isEqualTo(TABLE_ID);
        assertThat(result.externalFqn()).isEqualTo("poc.synthetic.cancer_registry");
        assertThat(result.externalVersion()).isEqualTo("0.2");
        server.verify();
    }

    @Test
    void refusesToOverwriteTableOwnedByAnotherDataset() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://openmetadata.test/api");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenMetadataGateway gateway = gateway(builder);
        UUID otherDataset = UUID.fromString("30000000-0000-0000-0000-000000000099");

        server.expect(requestTo("http://openmetadata.test/api/v1/metadata/types/name/table?fields=customProperties"))
                .andRespond(json(existingDefinitions()));
        server.expect(requestTo("http://openmetadata.test/api/v1/metadata/types/name/string"))
                .andRespond(json("{\"id\":\"" + STRING_TYPE_ID + "\"}"));
        server.expect(requestTo(
                        "http://openmetadata.test/api/v1/tables/name/poc.synthetic.cancer_registry?fields=extension"))
                .andRespond(json("{\"id\":\"" + TABLE_ID + "\",\"extension\":{\"platformDatasetId\":\""
                        + otherDataset + "\"}}"));

        assertThatThrownBy(() -> gateway.upsertDatasetVersion(command(DATASET_ID)))
                .isInstanceOf(MetadataMappingConflictException.class)
                .hasMessageContaining("different platform dataset");
        server.verify();
    }

    private static OpenMetadataGateway gateway(RestClient.Builder builder) {
        ServiceTokenProvider tokenProvider = () -> "synthetic-service-token";
        return new OpenMetadataRestGateway(builder.build(), tokenProvider, new ObjectMapper());
    }

    private static DatasetVersionMetadata command(UUID datasetId) {
        return new DatasetVersionMetadata(
                datasetId,
                VERSION_ID,
                "poc.synthetic.cancer_registry",
                List.of("肺癌", "乳腺癌"),
                List.of("STRUCTURED", "TEXT"),
                new DatasetVersionMetadata.QualitySummary(
                        new BigDecimal("92.50"), "A", DatasetVersionMetadata.GateResult.PASS));
    }

    private static org.springframework.test.web.client.RequestMatcher bearer() {
        return header(HttpHeaders.AUTHORIZATION, "Bearer synthetic-service-token");
    }

    private static org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String existingDefinitions() {
        String type = "\"propertyType\":{\"id\":\"" + STRING_TYPE_ID + "\",\"name\":\"string\"}";
        return "{\"id\":\"" + TABLE_TYPE_ID + "\",\"customProperties\":["
                + "{\"name\":\"platformDatasetId\"," + type + "},"
                + "{\"name\":\"platformVersionId\"," + type + "},"
                + "{\"name\":\"cancerTypes\"," + type + "},"
                + "{\"name\":\"modalities\"," + type + "},"
                + "{\"name\":\"qualitySummary\"," + type + "}]}";
    }

    private static String verifiedTable() {
        return "{\"id\":\"" + TABLE_ID
                + "\",\"fullyQualifiedName\":\"poc.synthetic.cancer_registry\",\"version\":0.2,"
                + "\"extension\":{"
                + "\"platformDatasetId\":\"" + DATASET_ID + "\","
                + "\"platformVersionId\":\"" + VERSION_ID + "\","
                + "\"cancerTypes\":\"[\\\"乳腺癌\\\",\\\"肺癌\\\"]\","
                + "\"modalities\":\"[\\\"STRUCTURED\\\",\\\"TEXT\\\"]\","
                + "\"qualitySummary\":\"{\\\"score\\\":92.50,\\\"grade\\\":\\\"A\\\",\\\"gate_result\\\":\\\"PASS\\\"}\"}}";
    }
}
