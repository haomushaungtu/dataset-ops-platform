package org.szah.dataset.platform.modules.supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.szah.dataset.platform.storage.QualificationMaterialStorage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class SupplierQualificationMaterialRepository {
    private final JdbcClient jdbc;

    SupplierQualificationMaterialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long nextVersion(UUID applicationId, String materialType) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                  FROM business.supplier_qualification_material
                 WHERE application_id = :applicationId AND material_type = :materialType
                """)
                .param("applicationId", applicationId)
                .param("materialType", materialType)
                .query(Long.class)
                .single();
    }

    void insert(UUID applicationId,
                SupplierQualificationMaterialView material,
                QualificationMaterialStorage.StoredObject stored) {
        jdbc.sql("""
                INSERT INTO business.supplier_qualification_material (
                    id, application_id, material_type, version_no, original_file_name, media_type,
                    size_bytes, sha256, storage_bucket, object_key, object_etag, object_version_id,
                    uploaded_by, uploaded_at
                ) VALUES (
                    :id, :applicationId, :materialType, :versionNo, :fileName, :mediaType,
                    :sizeBytes, :sha256, :bucket, :objectKey, :etag, :objectVersionId,
                    :uploadedBy, :uploadedAt
                )
                """)
                .param("id", material.id())
                .param("applicationId", applicationId)
                .param("materialType", material.materialType())
                .param("versionNo", material.versionNo())
                .param("fileName", material.originalFileName())
                .param("mediaType", material.mediaType())
                .param("sizeBytes", material.sizeBytes())
                .param("sha256", material.sha256())
                .param("bucket", stored.bucket())
                .param("objectKey", stored.objectKey())
                .param("etag", stored.etag())
                .param("objectVersionId", stored.versionId())
                .param("uploadedBy", material.uploadedBy())
                .param("uploadedAt", material.uploadedAt())
                .update();
    }

    List<SupplierQualificationMaterialView> findAll(UUID applicationId) {
        return jdbc.sql("""
                SELECT id, material_type, version_no, original_file_name, media_type,
                       size_bytes, sha256, uploaded_by, uploaded_at
                  FROM business.supplier_qualification_material
                 WHERE application_id = :applicationId
                 ORDER BY material_type, version_no DESC
                """)
                .param("applicationId", applicationId)
                .query(this::map)
                .list();
    }

    boolean existsForApplication(UUID applicationId) {
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM business.supplier_qualification_material
                 WHERE application_id = :applicationId
                """)
                .param("applicationId", applicationId)
                .query(Long.class)
                .single() > 0;
    }

    private SupplierQualificationMaterialView map(ResultSet rs, int rowNumber) throws SQLException {
        return new SupplierQualificationMaterialView(
                rs.getObject("id", UUID.class),
                rs.getString("material_type"),
                rs.getLong("version_no"),
                rs.getString("original_file_name"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("uploaded_by"),
                rs.getObject("uploaded_at", OffsetDateTime.class));
    }
}
