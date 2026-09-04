package org.szah.dataset.platform.modules.supplier;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SupplierApplicationView(
        UUID id,
        String applicationNo,
        String applicantId,
        String organizationName,
        String unifiedSocialCreditCode,
        String contactName,
        String contactPhone,
        SupplierApplicationStatus status,
        String processInstanceId,
        String reviewComment,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
