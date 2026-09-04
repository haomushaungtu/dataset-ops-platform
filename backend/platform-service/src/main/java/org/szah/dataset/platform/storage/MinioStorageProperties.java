package org.szah.dataset.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.storage.minio")
final class MinioStorageProperties {
    private boolean enabled;
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String prefix;

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    String getEndpoint() {
        return endpoint;
    }

    void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    String getAccessKey() {
        return accessKey;
    }

    void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    String getSecretKey() {
        return secretKey;
    }

    void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    String getBucket() {
        return bucket;
    }

    void setBucket(String bucket) {
        this.bucket = bucket;
    }

    String getPrefix() {
        return prefix;
    }

    void setPrefix(String prefix) {
        this.prefix = prefix;
    }
}
