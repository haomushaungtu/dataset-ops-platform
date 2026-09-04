package org.szah.dataset.integrations.openmetadata.sync;

public interface OpenMetadataGateway {
    MetadataSyncResult upsertDatasetVersion(DatasetVersionMetadata command);
}
