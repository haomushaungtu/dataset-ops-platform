package org.szah.dataset.integrations.openmetadata.sync;

public final class MetadataMappingConflictException extends MetadataSyncException {
    public MetadataMappingConflictException(String message) {
        super("OPENMETADATA_MAPPING_CONFLICT", message);
    }
}
