package org.szah.dataset.platform.modules.supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.szah.dataset.platform.common.api.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class SupplierApplicationService {
    private static final Set<SupplierApplicationStatus> RESUBMITTABLE = Set.of(
            SupplierApplicationStatus.DRAFT, SupplierApplicationStatus.RETURNED);

    private final SupplierApplicationRepository repository;
    private final SupplierQualificationMaterialRepository materials;
    private final IdempotencyLockRepository idempotencyLocks;
    private final SupplierWorkflow workflow;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SupplierApplicationService(SupplierApplicationRepository repository,
                                      SupplierQualificationMaterialRepository materials,
                                      IdempotencyLockRepository idempotencyLocks,
                                      SupplierWorkflow workflow,
                                      JdbcClient jdbc,
                                      ObjectMapper objectMapper) {
        this(repository, materials, idempotencyLocks, workflow, jdbc, objectMapper, Clock.systemUTC());
    }

    SupplierApplicationService(SupplierApplicationRepository repository,
                               SupplierQualificationMaterialRepository materials,
                               IdempotencyLockRepository idempotencyLocks,
                               SupplierWorkflow workflow,
                               JdbcClient jdbc,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.repository = repository;
        this.materials = materials;
        this.idempotencyLocks = idempotencyLocks;
        this.workflow = workflow;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public SupplierApplicationView create(SupplierApplicationController.CreateSupplierApplication command,
                                          String actorId,
                                          String idempotencyKey,
                                          String requestId) {
        String scope = "supplier-applications:create";
        String requestHash = sha256(writeJson(command));
        lockIdempotency(actorId, scope, idempotencyKey);
        SupplierApplicationView replay = replay(actorId, scope, idempotencyKey, requestHash);
        if (replay != null) {
            return replay;
        }

        OffsetDateTime now = now();
        UUID id = UUID.randomUUID();
        String applicationNo = "SUP-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + id.toString().substring(0, 8).toUpperCase();
        SupplierApplicationView created = new SupplierApplicationView(
                id, applicationNo, actorId, command.organizationName(), command.unifiedSocialCreditCode(),
                command.contactName(), command.contactPhone(), SupplierApplicationStatus.DRAFT,
                null, null, 0, now, now);
        repository.insert(created, writeJson(command));
        record(created, null, SupplierApplicationStatus.DRAFT, actorId, null,
                "supplier.application.created.v1", requestId);
        remember(actorId, scope, idempotencyKey, requestHash, created);
        return created;
    }

    public SupplierApplicationView get(UUID id, String actorId, boolean operator) {
        SupplierApplicationView application = repository.require(id);
        if (!operator && !application.applicantId().equals(actorId)) {
            throw new BusinessException("OBJECT_ACCESS_DENIED", "无权访问该供应商申请", FORBIDDEN);
        }
        return application;
    }

    @Transactional
    public SupplierApplicationView update(UUID id,
                                          SupplierApplicationController.UpdateSupplierApplication update,
                                          String actorId,
                                          long expectedVersion,
                                          String idempotencyKey,
                                          String requestId) {
        String requestFingerprint = expectedVersion + ":" + writeJson(update);
        return command(id, actorId, idempotencyKey, "update", requestFingerprint, requestId, current -> {
            requireOwner(current, actorId);
            requireVersion(current, expectedVersion);
            if (!Set.of(SupplierApplicationStatus.DRAFT, SupplierApplicationStatus.RETURNED)
                    .contains(current.status())) {
                throw stateConflict(current, "修改");
            }
            SupplierApplicationView result = repository.updateDetails(current, update, writeJson(update), now());
            record(result, current.status(), current.status(), actorId, null,
                    "supplier.application.updated.v1", requestId);
            return result;
        });
    }

    @Transactional
    public SupplierApplicationView submit(UUID id, String actorId, long expectedVersion,
                                          String idempotencyKey, String requestId) {
        return command(id, actorId, idempotencyKey, "submit", expectedVersion + ":submit", requestId, current -> {
            requireOwner(current, actorId);
            requireVersion(current, expectedVersion);
            if (!RESUBMITTABLE.contains(current.status())) {
                throw stateConflict(current, "提交");
            }
            if (!materials.existsForApplication(current.id())) {
                throw new BusinessException("QUALIFICATION_MATERIAL_REQUIRED",
                        "提交前必须至少上传一份资质材料", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            String processInstanceId = current.processInstanceId();
            if (current.status() == SupplierApplicationStatus.DRAFT) {
                repository.freezeSubmittedSnapshot(current.id());
                processInstanceId = workflow.start(current.id(), current.applicantId());
            } else {
                workflow.resubmit(processInstanceId, actorId);
            }
            SupplierApplicationView submitted = transition(current, SupplierApplicationStatus.SUBMITTED, processInstanceId, null,
                    actorId, null, "supplier.application.submitted.v1", requestId);
            return transition(submitted, SupplierApplicationStatus.UNDER_REVIEW, null, null,
                    "workflow", null, "supplier.application.review-started.v1", requestId);
        });
    }

    @Transactional
    public SupplierApplicationView withdraw(UUID id, String actorId, long expectedVersion,
                                            String idempotencyKey, String requestId) {
        return command(id, actorId, idempotencyKey, "withdraw", expectedVersion + ":withdraw", requestId, current -> {
            requireOwner(current, actorId);
            requireVersion(current, expectedVersion);
            if (!Set.of(SupplierApplicationStatus.DRAFT, SupplierApplicationStatus.RETURNED)
                    .contains(current.status())) {
                throw stateConflict(current, "撤回");
            }
            if (current.status() == SupplierApplicationStatus.RETURNED) {
                workflow.cancel(current.processInstanceId(), "withdrawn by applicant");
            }
            return transition(current, SupplierApplicationStatus.WITHDRAWN, null, null,
                    actorId, null, "supplier.application.withdrawn.v1", requestId);
        });
    }

    @Transactional
    public SupplierApplicationView decide(UUID id,
                                          String actorId,
                                          SupplierApplicationStatus decision,
                                          String comment,
                                          long expectedVersion,
                                          String idempotencyKey,
                                          String requestId) {
        if (!Set.of(SupplierApplicationStatus.APPROVED, SupplierApplicationStatus.RETURNED,
                SupplierApplicationStatus.REJECTED).contains(decision)) {
            throw new BusinessException("INVALID_DECISION", "不支持的审核结论", HttpStatus.BAD_REQUEST);
        }
        String requestFingerprint = expectedVersion + ":" + decision + ":" + (comment == null ? "" : comment);
        return command(id, actorId, idempotencyKey, decision.name().toLowerCase(), requestFingerprint, requestId, current -> {
            requireVersion(current, expectedVersion);
            requireState(current, SupplierApplicationStatus.UNDER_REVIEW, "审核");
            workflow.startReview(current.processInstanceId(), actorId);
            workflow.decide(current.processInstanceId(), actorId, decision);
            SupplierApplicationView result = transition(current, decision, null, comment, actorId,
                    comment, "supplier.application." + decision.name().toLowerCase().replace('_', '-') + ".v1", requestId);
            if (decision == SupplierApplicationStatus.APPROVED) {
                repository.createSupplier(result, now());
                recordOutbox(result, actorId, "supplier.identity-role.requested.v1", requestId,
                        writeJson(java.util.Map.of(
                                "supplier_application_id", result.id(),
                                "user_id", result.applicantId(),
                                "role", "SUPPLIER")));
            }
            return result;
        });
    }

    private SupplierApplicationView command(UUID id,
                                            String actorId,
                                            String idempotencyKey,
                                            String action,
                                            String requestFingerprint,
                                            String requestId,
                                            Command command) {
        requireIdempotencyKey(idempotencyKey);
        String scope = "supplier-applications:" + id + ":" + action;
        String requestHash = sha256(requestFingerprint);
        lockIdempotency(actorId, scope, idempotencyKey);
        SupplierApplicationView replay = replay(actorId, scope, idempotencyKey, requestHash);
        if (replay != null) {
            return replay;
        }
        SupplierApplicationView result = command.apply(repository.require(id));
        remember(actorId, scope, idempotencyKey, requestHash, result);
        return result;
    }

    private SupplierApplicationView transition(SupplierApplicationView current,
                                               SupplierApplicationStatus next,
                                               String processInstanceId,
                                               String comment,
                                               String actorId,
                                               String reason,
                                               String eventType,
                                               String requestId) {
        SupplierApplicationView result = repository.transition(current, next, processInstanceId, comment, now());
        record(result, current.status(), next, actorId, reason, eventType, requestId);
        return result;
    }

    private void record(SupplierApplicationView application,
                        SupplierApplicationStatus before,
                        SupplierApplicationStatus after,
                        String actorId,
                        String reason,
                        String eventType,
                        String requestId) {
        OffsetDateTime now = now();
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO business.supplier_application_state_history
                    (id, application_id, from_status, to_status, actor_id, reason, request_id,
                     business_version, occurred_at)
                VALUES (:id, :applicationId, :fromStatus, :toStatus, :actorId, :reason, :requestId,
                        :businessVersion, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("applicationId", application.id())
                .param("fromStatus", before == null ? null : before.name())
                .param("toStatus", after.name())
                .param("actorId", actorId)
                .param("reason", reason)
                .param("requestId", requestId)
                .param("businessVersion", application.version())
                .param("occurredAt", now)
                .update();
        jdbc.sql("""
                INSERT INTO audit.audit_event
                    (event_id, actor_id, action, object_type, object_id, before_state, after_state,
                     reason, request_id, business_version, occurred_at)
                VALUES (:eventId, :actorId, :action, 'supplier_application', :objectId,
                        :beforeState, :afterState, :reason, :requestId, :businessVersion, :occurredAt)
                """)
                .param("eventId", eventId)
                .param("actorId", actorId)
                .param("action", eventType)
                .param("objectId", application.id())
                .param("beforeState", before == null ? null : before.name())
                .param("afterState", after.name())
                .param("reason", reason)
                .param("requestId", requestId)
                .param("businessVersion", application.version())
                .param("occurredAt", now)
                .update();
        jdbc.sql("""
                INSERT INTO integration.outbox_event
                    (event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
                     correlation_id, actor_id, payload, occurred_at)
                VALUES (:eventId, :eventType, 'supplier_application', :aggregateId, :aggregateVersion,
                        :correlationId, :actorId, :payload, :occurredAt)
                """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("aggregateId", application.id())
                .param("aggregateVersion", application.version())
                .param("correlationId", requestId)
                .param("actorId", actorId)
                .param("payload", writeJson(java.util.Map.of(
                        "application_id", application.id(),
                        "application_no", application.applicationNo(),
                        "status", application.status(),
                        "version", application.version())))
                .param("occurredAt", now)
                .update();
    }

    private void recordOutbox(SupplierApplicationView application,
                              String actorId,
                              String eventType,
                              String requestId,
                              String payload) {
        jdbc.sql("""
                INSERT INTO integration.outbox_event
                    (event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
                     correlation_id, actor_id, payload, occurred_at)
                VALUES (:eventId, :eventType, 'supplier_application', :aggregateId, :aggregateVersion,
                        :correlationId, :actorId, :payload, :occurredAt)
                """)
                .param("eventId", UUID.randomUUID())
                .param("eventType", eventType)
                .param("aggregateId", application.id())
                .param("aggregateVersion", application.version())
                .param("correlationId", requestId)
                .param("actorId", actorId)
                .param("payload", payload)
                .param("occurredAt", now())
                .update();
    }

    private SupplierApplicationView replay(String actorId, String scope, String key, String requestHash) {
        requireIdempotencyKey(key);
        return jdbc.sql("""
                SELECT request_hash, response_json FROM integration.idempotency_record
                 WHERE actor_id = :actorId AND operation_scope = :scope AND idempotency_key = :key
                """)
                .param("actorId", actorId)
                .param("scope", scope)
                .param("key", key)
                .query((rs, rowNumber) -> {
                    if (!requestHash.equals(rs.getString("request_hash"))) {
                        throw new BusinessException("IDEMPOTENCY_CONFLICT",
                                "同一幂等键不能用于不同请求", CONFLICT);
                    }
                    try {
                        return objectMapper.readValue(rs.getString("response_json"), SupplierApplicationView.class);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("无法读取幂等响应", exception);
                    }
                })
                .optional()
                .orElse(null);
    }

    private void lockIdempotency(String actorId, String scope, String key) {
        requireIdempotencyKey(key);
        idempotencyLocks.lock(actorId, scope, key, now());
    }

    private void remember(String actorId,
                          String scope,
                          String key,
                          String requestHash,
                          SupplierApplicationView response) {
        jdbc.sql("""
                INSERT INTO integration.idempotency_record
                    (actor_id, operation_scope, idempotency_key, request_hash, response_json, created_at)
                VALUES (:actorId, :scope, :key, :requestHash, :responseJson, :createdAt)
                """)
                .param("actorId", actorId)
                .param("scope", scope)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("responseJson", writeJson(response))
                .param("createdAt", now())
                .update();
    }

    private void requireOwner(SupplierApplicationView application, String actorId) {
        if (!application.applicantId().equals(actorId)) {
            throw new BusinessException("OBJECT_ACCESS_DENIED", "只有申请人可以执行该操作", FORBIDDEN);
        }
    }

    private void requireState(SupplierApplicationView application,
                              SupplierApplicationStatus expected,
                              String action) {
        if (application.status() != expected) {
            throw stateConflict(application, action);
        }
    }

    private void requireVersion(SupplierApplicationView application, long expectedVersion) {
        if (application.version() != expectedVersion) {
            throw new BusinessException("CURRENT_VERSION_CONFLICT",
                    "请求版本 " + expectedVersion + " 已过期；当前版本为 " + application.version(), CONFLICT);
        }
    }

    private BusinessException stateConflict(SupplierApplicationView application, String action) {
        return new BusinessException("STATE_CONFLICT",
                "当前状态 " + application.status() + " 不允许" + action, CONFLICT);
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "必须提供有效的 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化业务记录", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface Command {
        SupplierApplicationView apply(SupplierApplicationView current);
    }
}
