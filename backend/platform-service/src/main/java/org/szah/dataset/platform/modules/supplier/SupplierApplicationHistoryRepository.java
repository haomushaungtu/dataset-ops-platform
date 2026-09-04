package org.szah.dataset.platform.modules.supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.szah.dataset.platform.common.api.BusinessException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
class SupplierApplicationHistoryRepository {
    private final JdbcClient jdbc;

    SupplierApplicationHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String requireApplicantId(UUID applicationId) {
        return jdbc.sql("""
                SELECT applicant_id
                  FROM business.supplier_application
                 WHERE id = :applicationId
                """)
                .param("applicationId", applicationId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "SUPPLIER_APPLICATION_NOT_FOUND", "供应商申请不存在", NOT_FOUND));
    }

    List<SupplierApplicationHistoryView> findByApplicationId(UUID applicationId) {
        return jdbc.sql("""
                SELECT from_status, to_status, actor_id, reason, request_id, business_version, occurred_at
                  FROM business.supplier_application_state_history
                 WHERE application_id = :applicationId
                 ORDER BY occurred_at ASC, business_version ASC, id ASC
                """)
                .param("applicationId", applicationId)
                .query(this::map)
                .list();
    }

    private SupplierApplicationHistoryView map(ResultSet rs, int rowNumber) throws SQLException {
        String fromStatus = rs.getString("from_status");
        return new SupplierApplicationHistoryView(
                fromStatus == null ? null : SupplierApplicationStatus.valueOf(fromStatus),
                SupplierApplicationStatus.valueOf(rs.getString("to_status")),
                rs.getString("actor_id"),
                rs.getString("reason"),
                rs.getString("request_id"),
                rs.getLong("business_version"),
                rs.getObject("occurred_at", OffsetDateTime.class));
    }
}
