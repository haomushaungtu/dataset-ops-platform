package org.szah.dataset.platform.modules.supplier;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SupplierQualificationMaterialView(
        UUID id,
        String materialType,
        long versionNo,
        String originalFileName,
        String mediaType,
        long sizeBytes,
        String sha256,
        String uploadedBy,
        OffsetDateTime uploadedAt) {
}
