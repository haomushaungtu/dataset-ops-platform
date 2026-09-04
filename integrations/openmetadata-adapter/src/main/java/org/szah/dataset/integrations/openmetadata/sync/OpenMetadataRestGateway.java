package org.szah.dataset.integrations.openmetadata.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.szah.dataset.integrations.openmetadata.auth.ServiceTokenProvider;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class OpenMetadataRestGateway implements OpenMetadataGateway {
    private static final MediaType JSON_PATCH = MediaType.parseMediaType("application/json-patch+json");
    private static final List<PropertyDefinition> DEFINITIONS = List.of(
            new PropertyDefinition("platformDatasetId", "Platform dataset ID",
                    "Stable dataset UUID owned by dataset-platform-service."),
            new PropertyDefinition("platformVersionId", "Platform version ID",
                    "Stable immutable dataset-version UUID owned by dataset-platform-service."),
            new PropertyDefinition("cancerTypes", "Cancer types",
                    "Canonical JSON array of declared cancer types."),
            new PropertyDefinition("modalities", "Modalities",
                    "Canonical JSON array of declared dataset modalities."),
            new PropertyDefinition("qualitySummary", "Quality summary",
                    "Canonical JSON object containing score, grade and quality gate result."));

    private final RestClient client;
    private final ServiceTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    public OpenMetadataRestGateway(
            @Qualifier("openMetadataRestClient") RestClient client,
            ServiceTokenProvider tokenProvider,
            ObjectMapper objectMapper) {
        this.client = client;
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public MetadataSyncResult upsertDatasetVersion(DatasetVersionMetadata command) {
        String token = tokenProvider.acquire();
        try {
            ensureDefinitions(token);
            ObjectNode table = getTableByName(token, command.openMetadataTableFqn());
            UUID tableId = requiredUuid(table, "id", "OpenMetadata table response");
            ObjectNode desired = desiredExtension(command);
            assertDatasetMappingIsCompatible(table.path("extension"), command.datasetId());
            patchExtension(token, tableId, table.path("extension"), desired);

            ObjectNode verified = getTableById(token, tableId);
            verifyExtension(verified.path("extension"), desired);
            return new MetadataSyncResult(
                    command.datasetId(),
                    command.versionId(),
                    "OPENMETADATA",
                    "TABLE",
                    tableId,
                    requiredText(verified, "fullyQualifiedName", "OpenMetadata table response"),
                    requiredValue(verified, "version", "OpenMetadata table verification response"),
                    MetadataSyncResult.SyncStatus.SYNCED,
                    OffsetDateTime.now(ZoneOffset.UTC));
        } catch (MetadataSyncException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new MetadataSyncException("OPENMETADATA_REQUEST_FAILED",
                    "OpenMetadata request failed with HTTP " + exception.getStatusCode().value(), exception);
        } catch (RestClientException exception) {
            throw new MetadataSyncException("OPENMETADATA_REQUEST_FAILED",
                    "OpenMetadata request failed", exception);
        }
    }

    private void ensureDefinitions(String token) {
        ObjectNode tableType = getType(token, "table", true);
        ObjectNode stringType = getType(token, "string", false);
        UUID tableTypeId = requiredUuid(tableType, "id", "OpenMetadata table type response");
        UUID stringTypeId = requiredUuid(stringType, "id", "OpenMetadata string type response");
        Set<String> existing = new LinkedHashSet<>();
        tableType.path("customProperties").forEach(property -> existing.add(property.path("name").asText()));

        boolean changed = false;
        for (PropertyDefinition definition : DEFINITIONS) {
            if (!existing.contains(definition.name())) {
                putDefinition(token, tableTypeId, stringTypeId, definition);
                changed = true;
            }
        }
        verifyDefinitions(changed ? getType(token, "table", true) : tableType, stringTypeId);
    }

    private static void verifyDefinitions(ObjectNode tableType, UUID stringTypeId) {
        Map<String, JsonNode> actual = new LinkedHashMap<>();
        tableType.path("customProperties")
                .forEach(property -> actual.put(property.path("name").asText(), property));
        for (PropertyDefinition definition : DEFINITIONS) {
            JsonNode property = actual.get(definition.name());
            if (property == null) {
                throw new MetadataSyncException("OPENMETADATA_DEFINITION_VERIFICATION_FAILED",
                        "OpenMetadata custom property was not created: " + definition.name());
            }
            JsonNode propertyType = property.path("propertyType");
            boolean expectedType = "string".equals(propertyType.path("name").asText())
                    || stringTypeId.toString().equals(propertyType.path("id").asText());
            if (!expectedType) {
                throw new MetadataMappingConflictException(
                        "OpenMetadata custom property has an incompatible type: " + definition.name());
            }
        }
    }

    private ObjectNode getType(String token, String name, boolean customProperties) {
        String suffix = customProperties ? "?fields=customProperties" : "";
        return requireObject(client.get()
                .uri("/v1/metadata/types/name/{name}" + suffix, name)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class), "OpenMetadata type response");
    }

    private void putDefinition(String token, UUID tableTypeId, UUID stringTypeId,
                               PropertyDefinition definition) {
        Map<String, Object> propertyType = new LinkedHashMap<>();
        propertyType.put("id", stringTypeId);
        propertyType.put("type", "type");
        propertyType.put("name", "string");
        propertyType.put("fullyQualifiedName", "string");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", definition.name());
        body.put("displayName", definition.displayName());
        body.put("description", definition.description());
        body.put("propertyType", propertyType);
        try {
            client.put()
                    .uri("/v1/metadata/types/{id}", tableTypeId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 409
                    || !hasDefinition(getType(token, "table", true), definition.name())) {
                throw exception;
            }
        }
    }

    private static boolean hasDefinition(ObjectNode tableType, String name) {
        for (JsonNode property : tableType.path("customProperties")) {
            if (name.equals(property.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode getTableByName(String token, String fqn) {
        return requireObject(client.get()
                .uri(builder -> builder.path("/v1/tables/name/{fqn}")
                        .queryParam("fields", "extension")
                        .build(fqn))
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class), "OpenMetadata table response");
    }

    private ObjectNode getTableById(String token, UUID id) {
        return requireObject(client.get()
                .uri(builder -> builder.path("/v1/tables/{id}")
                        .queryParam("fields", "extension")
                        .build(id))
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class), "OpenMetadata table verification response");
    }

    private void patchExtension(String token, UUID id, JsonNode currentExtension, ObjectNode desired) {
        List<Map<String, Object>> patch = new ArrayList<>();
        if (!currentExtension.isObject()) {
            patch.add(Map.of("op", "add", "path", "/extension", "value", desired));
        } else {
            desired.properties().forEach(property -> patch.add(Map.of(
                    "op", "add",
                    "path", "/extension/" + escapeJsonPointer(property.getKey()),
                    "value", property.getValue())));
        }
        client.patch()
                .uri("/v1/tables/{id}", id)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(JSON_PATCH)
                .body(patch)
                .retrieve()
                .toBodilessEntity();
    }

    private ObjectNode desiredExtension(DatasetVersionMetadata command) {
        ObjectNode extension = objectMapper.createObjectNode();
        extension.put("platformDatasetId", command.datasetId().toString());
        extension.put("platformVersionId", command.versionId().toString());
        extension.put("cancerTypes", canonicalJson(command.cancerTypes()));
        extension.put("modalities", canonicalJson(command.modalities()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("score", command.qualitySummary().score());
        summary.put("grade", command.qualitySummary().grade());
        summary.put("gate_result", command.qualitySummary().gateResult().name());
        extension.put("qualitySummary", canonicalJson(summary));
        return extension;
    }

    private String canonicalJson(List<String> values) {
        List<String> normalized = values.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return canonicalJson((Object) normalized);
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new MetadataSyncException("METADATA_SERIALIZATION_FAILED",
                    "Dataset metadata could not be serialized", exception);
        }
    }

    private static void assertDatasetMappingIsCompatible(JsonNode extension, UUID datasetId) {
        if (!extension.isObject()) {
            return;
        }
        String existing = extension.path("platformDatasetId").asText(null);
        if (existing != null && !existing.equals(datasetId.toString())) {
            throw new MetadataMappingConflictException(
                    "OpenMetadata table is already mapped to a different platform dataset");
        }
    }

    private static void verifyExtension(JsonNode actual, ObjectNode desired) {
        if (!actual.isObject()) {
            throw new MetadataSyncException("OPENMETADATA_VERIFICATION_FAILED",
                    "OpenMetadata did not return the synchronized extension");
        }
        desired.properties().forEach(expected -> {
            if (!expected.getValue().equals(actual.get(expected.getKey()))) {
                throw new MetadataSyncException("OPENMETADATA_VERIFICATION_FAILED",
                        "OpenMetadata verification failed for custom property " + expected.getKey());
            }
        });
    }

    private static ObjectNode requireObject(JsonNode node, String source) {
        if (!(node instanceof ObjectNode object)) {
            throw new MetadataSyncException("OPENMETADATA_RESPONSE_INVALID", source + " was not a JSON object");
        }
        return object;
    }

    private static UUID requiredUuid(ObjectNode node, String field, String source) {
        try {
            return UUID.fromString(requiredText(node, field, source));
        } catch (IllegalArgumentException exception) {
            throw new MetadataSyncException("OPENMETADATA_RESPONSE_INVALID",
                    source + " contained an invalid " + field, exception);
        }
    }

    private static String requiredText(ObjectNode node, String field, String source) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new MetadataSyncException("OPENMETADATA_RESPONSE_INVALID",
                    source + " did not contain " + field);
        }
        return value;
    }

    private static String requiredValue(ObjectNode node, String field, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new MetadataSyncException("OPENMETADATA_RESPONSE_INVALID",
                    source + " did not contain " + field);
        }
        return value.asText();
    }

    private static String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record PropertyDefinition(String name, String displayName, String description) {
    }
}
