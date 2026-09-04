package org.szah.dataset.platform.storage;

import java.nio.file.Path;
import java.util.UUID;

final class DisabledQualificationMaterialStorage implements QualificationMaterialStorage {
    @Override
    public StoredObject store(UUID applicationId, UUID materialId, Path source, long size,
                              String mediaType, String sha256) {
        throw new QualificationMaterialStorageException("supplier qualification material storage is disabled");
    }

    @Override
    public void delete(StoredObject object) {
        // Nothing was stored while the adapter was disabled.
    }
}
