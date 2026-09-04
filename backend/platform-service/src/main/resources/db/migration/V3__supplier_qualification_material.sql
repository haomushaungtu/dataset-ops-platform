CREATE TABLE business.supplier_qualification_material (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES business.supplier_application(id),
    material_type VARCHAR(64) NOT NULL,
    version_no BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    sha256 CHAR(64) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(700) NOT NULL,
    object_etag VARCHAR(255),
    object_version_id VARCHAR(255),
    uploaded_by VARCHAR(128) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_supplier_material_version UNIQUE (application_id, material_type, version_no),
    CONSTRAINT uk_supplier_material_object UNIQUE (storage_bucket, object_key)
);

CREATE INDEX idx_supplier_material_application
    ON business.supplier_qualification_material (application_id, material_type, version_no DESC);
