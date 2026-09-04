package org.szah.dataset.platform.integration.openmetadata;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OpenMetadataSyncRequest(
        @NotNull UUID datasetId,
        @NotNull UUID versionId,
        @NotBlank @Size(max = 1024) String openMetadataTableFqn,
        @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 100) String> cancerTypes,
        @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 100) String> modalities,
        @NotNull @Valid QualitySummary qualitySummary) {

    public record QualitySummary(
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal score,
            @NotBlank @Size(max = 32) String grade,
            @NotNull GateResult gateResult) {
    }

    public enum GateResult {
        PASS,
        FAIL,
        PENDING_REVIEW
    }
}
