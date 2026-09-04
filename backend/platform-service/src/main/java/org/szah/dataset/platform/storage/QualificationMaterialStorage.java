package org.szah.dataset.platform.storage;

import java.nio.file.Path;
import java.util.UUID;

public interface QualificationMaterialStorage {
    StoredObject store(UUID applicationId,
                       UUID materialId,
                       Path source,
                       long size,
                       String mediaType,
                       String sha256);

    void delete(StoredObject object);

    record StoredObject(String bucket, String objectKey, String etag, String versionId) {
    }
}
