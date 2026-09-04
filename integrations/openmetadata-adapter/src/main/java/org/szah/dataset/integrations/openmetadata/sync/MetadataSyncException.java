package org.szah.dataset.integrations.openmetadata.sync;

public class MetadataSyncException extends RuntimeException {
    private final String code;

    public MetadataSyncException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MetadataSyncException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
