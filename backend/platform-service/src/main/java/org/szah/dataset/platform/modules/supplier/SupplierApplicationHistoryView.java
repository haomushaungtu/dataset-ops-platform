package org.szah.dataset.platform.modules.supplier;

import java.time.OffsetDateTime;

public record SupplierApplicationHistoryView(
        SupplierApplicationStatus fromStatus,
        SupplierApplicationStatus toStatus,
        String actorId,
        String reason,
        String requestId,
        long businessVersion,
        OffsetDateTime occurredAt) {
}
