package org.szah.dataset.platform.integration.openmetadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

@Service
public class OpenMetadataSyncOutboxService {
    public static final String EVENT_TYPE = "dataset.version.openmetadata-sync-requested.v1";
    private static final String EXTERNAL_SYSTEM = "OPENMETADATA";
    private static final String RESOURCE_TYPE = "TABLE";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;

    @Autowired
    public OpenMetadataSyncOutboxService(JdbcClient jdbc, ObjectMapper objectMapper, Validator validator) {
        this(jdbc, objectMapper, validator, Clock.systemUTC());
    }

    OpenMetadataSyncOutboxService(JdbcClient jdbc, ObjectMapper objectMapper, Validator validator, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Joins an existing business transaction (or starts one) so the mapping intent and Outbox event
     * commit or roll back together with the dataset-version state change that calls this method.
     */
    @Transactional
    public UUID enqueue(OpenMetadataSyncRequest request,
                        long aggregateVersion,
                        String correlationId,
                        String actorId) {
        validate(request, aggregateVersion, correlationId, actorId);
        ExistingMapping existing = jdbc.sql("""
                SELECT source_event_id, external_fqn
                  FROM integration.external_resource_mapping
                 WHERE external_system = :system
                   AND resource_type = :resourceType
                   AND business_id = :businessId
                 FOR UPDATE
                """)
                .param("system", EXTERNAL_SYSTEM)
                .param("resourceType", RESOURCE_TYPE)
                .param("businessId", request.versionId())
                .query((rs, row) -> new ExistingMapping(
                        rs.getObject("source_event_id", UUID.class), rs.getString("external_fqn")))
                .optional()
                .orElse(null);
        if (existing != null) {
            if (!existing.externalFqn().equals(request.openMetadataTableFqn())) {
                throw new IllegalStateException("dataset version already targets another OpenMetadata FQN");
            }
            return existing.eventId();
        }

        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO integration.outbox_event
                    (event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
                     correlation_id, actor_id, payload, occurred_at)
                VALUES (:eventId, :eventType, 'dataset_version', :aggregateId, :aggregateVersion,
                        :correlationId, :actorId, :payload, :occurredAt)
                """)
                .param("eventId", eventId)
                .param("eventType", EVENT_TYPE)
                .param("aggregateId", request.versionId())
                .param("aggregateVersion", aggregateVersion)
                .param("correlationId", correlationId)
                .param("actorId", actorId)
                .param("payload", writeJson(request))
                .param("occurredAt", now)
                .update();
        jdbc.sql("""
                INSERT INTO integration.external_resource_mapping
                    (mapping_id, external_system, resource_type, business_id, external_fqn,
                     sync_status, source_event_id, updated_at)
                VALUES (:mappingId, :system, :resourceType, :businessId, :externalFqn,
                        'PENDING', :sourceEventId, :updatedAt)
                """)
                .param("mappingId", UUID.randomUUID())
                .param("system", EXTERNAL_SYSTEM)
                .param("resourceType", RESOURCE_TYPE)
                .param("businessId", request.versionId())
                .param("externalFqn", request.openMetadataTableFqn())
                .param("sourceEventId", eventId)
                .param("updatedAt", now)
                .update();
        return eventId;
    }

    private void validate(OpenMetadataSyncRequest request,
                          long aggregateVersion,
                          String correlationId,
                          String actorId) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        Set<ConstraintViolation<OpenMetadataSyncRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("invalid OpenMetadata sync request: "
                    + violations.iterator().next().getPropertyPath());
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            throw new IllegalArgumentException("correlationId must contain 1 to 128 characters");
        }
        if (actorId == null || actorId.isBlank() || actorId.length() > 128) {
            throw new IllegalArgumentException("actorId must contain 1 to 128 characters");
        }
    }

    private String writeJson(OpenMetadataSyncRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize OpenMetadata sync request", exception);
        }
    }

    private record ExistingMapping(UUID eventId, String externalFqn) {
    }
}
