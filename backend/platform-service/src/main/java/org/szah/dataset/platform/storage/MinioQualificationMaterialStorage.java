package org.szah.dataset.platform.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class MinioQualificationMaterialStorage implements QualificationMaterialStorage {
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");

    private final MinioClient client;
    private final String bucket;
    private final String prefix;

    MinioQualificationMaterialStorage(MinioStorageProperties properties) {
        requireEnabledConfiguration(properties);
        this.bucket = properties.getBucket();
        this.prefix = properties.getPrefix();
        this.client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                throw new IllegalStateException("configured MinIO bucket does not exist");
            }
        } catch (Exception exception) {
            closeQuietly();
            throw new IllegalStateException("configured MinIO bucket is not accessible", exception);
        }
    }

    @Override
    public StoredObject store(UUID applicationId,
                              UUID materialId,
                              Path source,
                              long size,
                              String mediaType,
                              String sha256) {
        String objectKey = prefix + "/supplier-applications/" + applicationId
                + "/qualification-materials/" + materialId;
        try (InputStream input = Files.newInputStream(source)) {
            var response = client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, size, -1L)
                    .contentType(mediaType)
                    .userMetadata(Map.of("sha256", sha256))
                    .build());
            return new StoredObject(bucket, objectKey, response.etag(), response.versionId());
        } catch (Exception exception) {
            throw new QualificationMaterialStorageException("failed to store qualification material", exception);
        }
    }

    @Override
    public void delete(StoredObject object) {
        try {
            var builder = RemoveObjectArgs.builder().bucket(object.bucket()).object(object.objectKey());
            if (object.versionId() != null && !object.versionId().isBlank()) {
                builder.versionId(object.versionId());
            }
            client.removeObject(builder.build());
        } catch (Exception exception) {
            throw new QualificationMaterialStorageException("failed to compensate qualification material", exception);
        }
    }

    @PreDestroy
    void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            client.close();
        } catch (Exception ignored) {
            // Closing a failed client cannot expose credentials or block application shutdown.
        }
    }

    private static void requireEnabledConfiguration(MinioStorageProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MinIO adapter cannot start while disabled");
        }
        URI endpoint;
        try {
            endpoint = URI.create(requireText(properties.getEndpoint(), "platform.storage.minio.endpoint"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("platform.storage.minio.endpoint is invalid", exception);
        }
        if (!endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || (endpoint.getPath() != null && !endpoint.getPath().isBlank() && !"/".equals(endpoint.getPath()))) {
            throw new IllegalStateException("platform.storage.minio.endpoint must be an absolute HTTP(S) origin");
        }
        requireText(properties.getAccessKey(), "platform.storage.minio.access-key");
        requireText(properties.getSecretKey(), "platform.storage.minio.secret-key");
        if (!BUCKET.matcher(requireText(properties.getBucket(), "platform.storage.minio.bucket")).matches()) {
            throw new IllegalStateException("platform.storage.minio.bucket is invalid");
        }
        if (!PREFIX.matcher(requireText(properties.getPrefix(), "platform.storage.minio.prefix")).matches()) {
            throw new IllegalStateException("platform.storage.minio.prefix contains an unsafe path");
        }
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when MinIO is enabled");
        }
        return value;
    }
}
