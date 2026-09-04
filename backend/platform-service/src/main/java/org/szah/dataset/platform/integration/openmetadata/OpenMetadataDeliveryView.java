package org.szah.dataset.platform.integration.openmetadata;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OpenMetadataDeliveryView(
        UUID eventId,
        UUID businessId,
        String externalFqn,
        String externalId,
        String externalVersion,
        String syncStatus,
        int attempts,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime publishedAt,
        OffsetDateTime failedAt,
        String lastErrorCode,
        String lastErrorMessage,
        List<Attempt> attemptHistory) {

    public record Attempt(
            int attemptNo,
            String outcome,
            String errorCode,
            String errorMessage,
            OffsetDateTime attemptedAt,
            OffsetDateTime nextAttemptAt) {
    }
}
