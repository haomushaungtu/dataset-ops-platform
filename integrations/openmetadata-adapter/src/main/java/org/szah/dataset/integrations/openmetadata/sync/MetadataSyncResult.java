package org.szah.dataset.integrations.openmetadata.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MetadataSyncResult(
        UUID datasetId,
        UUID versionId,
        String externalSystem,
        String resourceType,
        UUID externalId,
        String externalFqn,
        String externalVersion,
        SyncStatus status,
        OffsetDateTime syncedAt) {

    public enum SyncStatus {
        SYNCED
    }
}
