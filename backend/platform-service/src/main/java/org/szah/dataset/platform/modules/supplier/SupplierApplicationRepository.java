package org.szah.dataset.platform.modules.supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.szah.dataset.platform.common.api.BusinessException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
class SupplierApplicationRepository {
    private final JdbcClient jdbc;

    SupplierApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(SupplierApplicationView application, String organizationSnapshot) {
        jdbc.sql("""
                INSERT INTO business.supplier_application (
                    id, application_no, applicant_id, organization_name, unified_social_credit_code,
                    contact_name, contact_phone, organization_snapshot, status, version, created_at, updated_at
                ) VALUES (
                    :id, :applicationNo, :applicantId, :organizationName, :creditCode,
                    :contactName, :contactPhone, :snapshot, :status, :version, :createdAt, :updatedAt
                )
                """)
                .param("id", application.id())
                .param("applicationNo", application.applicationNo())
                .param("applicantId", application.applicantId())
                .param("organizationName", application.organizationName())
                .param("creditCode", application.unifiedSocialCreditCode())
                .param("contactName", application.contactName())
                .param("contactPhone", application.contactPhone())
                .param("snapshot", organizationSnapshot)
                .param("status", application.status().name())
                .param("version", application.version())
                .param("createdAt", application.createdAt())
                .param("updatedAt", application.updatedAt())
                .update();
    }

    SupplierApplicationView require(UUID id) {
        return find(id).orElseThrow(() -> new BusinessException(
                "SUPPLIER_APPLICATION_NOT_FOUND", "供应商申请不存在", NOT_FOUND));
    }

    Optional<SupplierApplicationView> find(UUID id) {
        return jdbc.sql("SELECT * FROM business.supplier_application WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    SupplierApplicationView updateDetails(
            SupplierApplicationView current,
            SupplierApplicationController.UpdateSupplierApplication update,
            String organizationSnapshot,
            OffsetDateTime now) {
        int updated = jdbc.sql("""
                UPDATE business.supplier_application
                   SET organization_name = :organizationName,
                       unified_social_credit_code = :creditCode,
                       contact_name = :contactName,
                       contact_phone = :contactPhone,
                       organization_snapshot = :organizationSnapshot,
                       version = version + 1,
                       updated_at = :updatedAt
                 WHERE id = :id AND status = :status AND version = :version
                """)
                .param("organizationName", update.organizationName())
                .param("creditCode", update.unifiedSocialCreditCode())
                .param("contactName", update.contactName())
                .param("contactPhone", update.contactPhone())
                .param("organizationSnapshot", organizationSnapshot)
                .param("updatedAt", now)
                .param("id", current.id())
                .param("status", current.status().name())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new BusinessException("VERSION_CONFLICT", "申请已被其他操作更新，请刷新后重试", CONFLICT);
        }
        return require(current.id());
    }

    SupplierApplicationView touchForMaterial(SupplierApplicationView current, OffsetDateTime now) {
        int updated = jdbc.sql("""
                UPDATE business.supplier_application
                   SET version = version + 1,
                       updated_at = :updatedAt
                 WHERE id = :id AND status = :status AND version = :version
                """)
                .param("updatedAt", now)
                .param("id", current.id())
                .param("status", current.status().name())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new BusinessException("VERSION_CONFLICT", "申请已被其他操作更新，请刷新后重试", CONFLICT);
        }
        return require(current.id());
    }

    SupplierApplicationView transition(SupplierApplicationView current,
                                       SupplierApplicationStatus next,
                                       String processInstanceId,
                                       String reviewComment,
                                       OffsetDateTime now) {
        int updated = jdbc.sql("""
                UPDATE business.supplier_application
                   SET status = :next,
                       process_instance_id = COALESCE(:processInstanceId, process_instance_id),
                       review_comment = COALESCE(:reviewComment, review_comment),
                       version = version + 1,
                       updated_at = :updatedAt
                 WHERE id = :id AND status = :expected AND version = :version
                """)
                .param("next", next.name())
                .param("processInstanceId", processInstanceId)
                .param("reviewComment", reviewComment)
                .param("updatedAt", now)
                .param("id", current.id())
                .param("expected", current.status().name())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new BusinessException("VERSION_CONFLICT", "申请已被其他操作更新，请刷新后重试", CONFLICT);
        }
        return require(current.id());
    }

    void freezeSubmittedSnapshot(UUID id) {
        jdbc.sql("""
                UPDATE business.supplier_application
                   SET submitted_snapshot = organization_snapshot
                 WHERE id = :id AND submitted_snapshot IS NULL
                """)
                .param("id", id)
                .update();
    }

    void createSupplier(SupplierApplicationView application, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO business.supplier (
                    id, supplier_no, application_id, organization_name, unified_social_credit_code,
                    status, version, created_at, updated_at
                ) VALUES (
                    :id, :supplierNo, :applicationId, :organizationName, :creditCode,
                    'ACTIVE', 0, :createdAt, :updatedAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("supplierNo", "S-" + application.applicationNo().substring(4))
                .param("applicationId", application.id())
                .param("organizationName", application.organizationName())
                .param("creditCode", application.unifiedSocialCreditCode())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        jdbc.sql("""
                INSERT INTO business.supplier_member (supplier_id, user_id, member_role, created_at)
                SELECT id, :userId, 'OWNER', :createdAt FROM business.supplier WHERE application_id = :applicationId
                """)
                .param("userId", application.applicantId())
                .param("createdAt", now)
                .param("applicationId", application.id())
                .update();
    }

    private SupplierApplicationView map(ResultSet rs, int rowNumber) throws SQLException {
        return new SupplierApplicationView(
                rs.getObject("id", UUID.class),
                rs.getString("application_no"),
                rs.getString("applicant_id"),
                rs.getString("organization_name"),
                rs.getString("unified_social_credit_code"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                SupplierApplicationStatus.valueOf(rs.getString("status")),
                rs.getString("process_instance_id"),
                rs.getString("review_comment"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
