package org.szah.dataset.platform.modules.supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.szah.dataset.platform.common.api.BusinessException;
import org.szah.dataset.platform.storage.QualificationMaterialStorage;
import org.szah.dataset.platform.storage.QualificationMaterialStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class SupplierQualificationMaterialService {
    static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final Pattern MATERIAL_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Logger LOG = LoggerFactory.getLogger(SupplierQualificationMaterialService.class);

    private final SupplierApplicationRepository applications;
    private final SupplierQualificationMaterialRepository materials;
    private final IdempotencyLockRepository idempotencyLocks;
    private final QualificationMaterialStorage storage;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SupplierQualificationMaterialService(SupplierApplicationRepository applications,
                                                SupplierQualificationMaterialRepository materials,
                                                IdempotencyLockRepository idempotencyLocks,
                                                QualificationMaterialStorage storage,
                                                JdbcClient jdbc,
                                                ObjectMapper objectMapper) {
        this(applications, materials, idempotencyLocks, storage, jdbc, objectMapper, Clock.systemUTC());
    }

    SupplierQualificationMaterialService(SupplierApplicationRepository applications,
                                         SupplierQualificationMaterialRepository materials,
                                         IdempotencyLockRepository idempotencyLocks,
                                         QualificationMaterialStorage storage,
                                         JdbcClient jdbc,
                                         ObjectMapper objectMapper,
                                         Clock clock) {
        this.applications = applications;
        this.materials = materials;
        this.idempotencyLocks = idempotencyLocks;
        this.storage = storage;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public MaterialUploadOutcome upload(UUID applicationId,
                                        String materialType,
                                        MultipartFile file,
                                        String actorId,
                                        long expectedVersion,
                                        String idempotencyKey,
                                        String requestId) {
        requireMaterialType(materialType);
        SupplierApplicationView accessCheck = applications.require(applicationId);
        requireOwner(accessCheck, actorId);
        try (PreparedMaterial prepared = prepare(file)) {
            String scope = "supplier-applications:" + applicationId + ":materials:upload";
            String requestHash = sha256(expectedVersion + ":" + materialType + ":"
                    + prepared.fileName() + ":" + prepared.mediaType() + ":"
                    + prepared.size() + ":" + prepared.sha256());
            requireIdempotencyKey(idempotencyKey);
            idempotencyLocks.lock(actorId, scope, idempotencyKey, now());
            MaterialUploadResult replay = replay(actorId, scope, idempotencyKey, requestHash);
            if (replay != null) {
                return new MaterialUploadOutcome(replay, false);
            }

            SupplierApplicationView current = applications.require(applicationId);
            requireOwner(current, actorId);
            requireVersion(current, expectedVersion);
            requireEditable(current);

            UUID materialId = stableMaterialId(actorId, scope, idempotencyKey);
            QualificationMaterialStorage.StoredObject stored;
            try {
                stored = storage.store(applicationId, materialId, prepared.path(), prepared.size(),
                        prepared.mediaType(), prepared.sha256());
            } catch (QualificationMaterialStorageException exception) {
                throw new BusinessException("MATERIAL_STORAGE_UNAVAILABLE",
                        "资质材料存储暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }
            registerRollbackCompensation(stored);

            SupplierApplicationView updatedApplication = applications.touchForMaterial(current, now());
            long materialVersion = materials.nextVersion(applicationId, materialType);
            SupplierQualificationMaterialView material = new SupplierQualificationMaterialView(
                    materialId, materialType, materialVersion, prepared.fileName(), prepared.mediaType(),
                    prepared.size(), prepared.sha256(), actorId, now());
            materials.insert(applicationId, material, stored);
            record(updatedApplication, material, actorId, requestId);

            MaterialUploadResult result = new MaterialUploadResult(material, updatedApplication.version());
            remember(actorId, scope, idempotencyKey, requestHash, result);
            return new MaterialUploadOutcome(result, true);
        }
    }

    public List<SupplierQualificationMaterialView> list(UUID applicationId, String actorId, boolean operator) {
        SupplierApplicationView application = applications.require(applicationId);
        if (!operator && !application.applicantId().equals(actorId)) {
            throw new BusinessException("OBJECT_ACCESS_DENIED", "无权查看该供应商申请的资质材料", FORBIDDEN);
        }
        return materials.findAll(applicationId);
    }

    private void record(SupplierApplicationView application,
                        SupplierQualificationMaterialView material,
                        String actorId,
                        String requestId) {
        OffsetDateTime occurredAt = now();
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO business.supplier_application_state_history
                    (id, application_id, from_status, to_status, actor_id, reason, request_id,
                     business_version, occurred_at)
                VALUES (:id, :applicationId, :status, :status, :actorId, :reason, :requestId,
                        :businessVersion, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("applicationId", application.id())
                .param("status", application.status().name())
                .param("actorId", actorId)
                .param("reason", "material:" + material.materialType() + ":v" + material.versionNo())
                .param("requestId", requestId)
                .param("businessVersion", application.version())
                .param("occurredAt", occurredAt)
                .update();
        jdbc.sql("""
                INSERT INTO audit.audit_event
                    (event_id, actor_id, action, object_type, object_id, before_state, after_state,
                     reason, request_id, business_version, occurred_at)
                VALUES (:eventId, :actorId, 'supplier.qualification-material.uploaded.v1',
                        'supplier_application', :objectId, :status, :status, :reason, :requestId,
                        :businessVersion, :occurredAt)
                """)
                .param("eventId", eventId)
                .param("actorId", actorId)
                .param("objectId", application.id())
                .param("status", application.status().name())
                .param("reason", "material:" + material.materialType() + ":v" + material.versionNo())
                .param("requestId", requestId)
                .param("businessVersion", application.version())
                .param("occurredAt", occurredAt)
                .update();
        jdbc.sql("""
                INSERT INTO integration.outbox_event
                    (event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
                     correlation_id, actor_id, payload, occurred_at)
                VALUES (:eventId, 'supplier.qualification-material.uploaded.v1', 'supplier_application',
                        :aggregateId, :aggregateVersion, :correlationId, :actorId, :payload, :occurredAt)
                """)
                .param("eventId", eventId)
                .param("aggregateId", application.id())
                .param("aggregateVersion", application.version())
                .param("correlationId", requestId)
                .param("actorId", actorId)
                .param("payload", writeJson(Map.of(
                        "application_id", application.id(),
                        "status", application.status(),
                        "version", application.version(),
                        "material_id", material.id(),
                        "material_type", material.materialType(),
                        "material_version", material.versionNo(),
                        "media_type", material.mediaType(),
                        "size_bytes", material.sizeBytes(),
                        "sha256", material.sha256())))
                .param("occurredAt", occurredAt)
                .update();
    }

    private PreparedMaterial prepare(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("MATERIAL_FILE_REQUIRED", "必须上传非空资质材料", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("MATERIAL_FILE_TOO_LARGE", "资质材料不能超过 10 MiB",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
        Path path;
        try {
            path = createPrivateTempFile();
        } catch (IOException exception) {
            throw new BusinessException("MATERIAL_PROCESSING_FAILED", "无法处理资质材料",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(file, path, digest);
            String mediaType = detectMediaType(path);
            return new PreparedMaterial(path, size, mediaType,
                    HexFormat.of().formatHex(digest.digest()), safeFileName(file.getOriginalFilename()));
        } catch (BusinessException exception) {
            deleteTemp(path);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteTemp(path);
            throw new BusinessException("MATERIAL_PROCESSING_FAILED", "无法处理资质材料",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private long copyBounded(MultipartFile file, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = file.getInputStream();
             OutputStream output = new DigestOutputStream(Files.newOutputStream(target), digest)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SIZE) {
                    throw new BusinessException("MATERIAL_FILE_TOO_LARGE", "资质材料不能超过 10 MiB",
                            HttpStatus.PAYLOAD_TOO_LARGE);
                }
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new BusinessException("MATERIAL_FILE_REQUIRED", "必须上传非空资质材料", HttpStatus.BAD_REQUEST);
        }
        return total;
    }

    private String detectMediaType(Path path) throws IOException {
        byte[] header = new byte[8];
        int read;
        try (InputStream input = Files.newInputStream(path)) {
            read = input.read(header);
        }
        if (read >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                && header[3] == 'F' && header[4] == '-') {
            return "application/pdf";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (read >= png.length && java.util.Arrays.equals(header, png)) {
            return "image/png";
        }
        if (read >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        throw new BusinessException("MATERIAL_FILE_TYPE_NOT_ALLOWED",
                "资质材料只允许 PDF、PNG 或 JPEG", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private MaterialUploadResult replay(String actorId, String scope, String key, String requestHash) {
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
                        return objectMapper.readValue(rs.getString("response_json"), MaterialUploadResult.class);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("无法读取材料上传幂等响应", exception);
                    }
                })
                .optional()
                .orElse(null);
    }

    private void remember(String actorId, String scope, String key, String requestHash, MaterialUploadResult response) {
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

    private void registerRollbackCompensation(QualificationMaterialStorage.StoredObject stored) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.delete(stored);
                    } catch (QualificationMaterialStorageException exception) {
                        LOG.error("Failed to compensate qualification material object {}", stored.objectKey(), exception);
                    }
                }
            }
        });
    }

    private UUID stableMaterialId(String actorId, String scope, String key) {
        return UUID.nameUUIDFromBytes((actorId + "\n" + scope + "\n" + key).getBytes(StandardCharsets.UTF_8));
    }

    private void requireOwner(SupplierApplicationView application, String actorId) {
        if (!application.applicantId().equals(actorId)) {
            throw new BusinessException("OBJECT_ACCESS_DENIED", "只有申请人可以上传资质材料", FORBIDDEN);
        }
    }

    private void requireVersion(SupplierApplicationView application, long expectedVersion) {
        if (application.version() != expectedVersion) {
            throw new BusinessException("CURRENT_VERSION_CONFLICT",
                    "请求版本 " + expectedVersion + " 已过期；当前版本为 " + application.version(), CONFLICT);
        }
    }

    private void requireEditable(SupplierApplicationView application) {
        if (application.status() != SupplierApplicationStatus.DRAFT
                && application.status() != SupplierApplicationStatus.RETURNED) {
            throw new BusinessException("STATE_CONFLICT",
                    "当前状态 " + application.status() + " 不允许上传资质材料", CONFLICT);
        }
    }

    private void requireMaterialType(String materialType) {
        if (materialType == null || !MATERIAL_TYPE.matcher(materialType).matches()) {
            throw new BusinessException("INVALID_MATERIAL_TYPE",
                    "material_type 必须是大写字母、数字和下划线组成的稳定代码", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "必须提供有效的 Idempotency-Key",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private String safeFileName(String original) {
        String name = original == null ? "material" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.isBlank()) {
            name = "material";
        }
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化材料记录", exception);
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

    private static void deleteTemp(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The OS temporary directory cleanup remains the final fallback.
        }
    }

    private static Path createPrivateTempFile() throws IOException {
        try {
            return Files.createTempFile("supplier-material-", ".upload",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile("supplier-material-", ".upload");
        }
    }

    public record MaterialUploadResult(SupplierQualificationMaterialView material, long applicationVersion) {
    }

    public record MaterialUploadOutcome(MaterialUploadResult result, boolean created) {
    }

    private record PreparedMaterial(Path path,
                                    long size,
                                    String mediaType,
                                    String sha256,
                                    String fileName) implements AutoCloseable {
        @Override
        public void close() {
            deleteTemp(path);
        }
    }
}
