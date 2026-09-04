package org.szah.dataset.integrations.openmetadata.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "adapter")
public record AdapterProperties(
        @NotNull @Valid Security security,
        @NotNull @Valid Iam iam,
        @NotNull @Valid OpenMetadata openmetadata) {

    public record Security(@NotNull URI issuer, @NotBlank String audience) {
        @AssertTrue(message = "adapter.security.issuer must be an absolute HTTP(S) URI")
        public boolean isIssuerValid() {
            return isHttpUri(issuer);
        }
    }

    public record Iam(
            @NotNull URI tokenUri,
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotBlank String scope) {
        @AssertTrue(message = "adapter.iam.token-uri must be an absolute HTTP(S) URI")
        public boolean isTokenUriValid() {
            return isHttpUri(tokenUri);
        }
    }

    public record OpenMetadata(
            @NotNull URI baseUrl,
            @NotNull @PositiveDuration Duration connectTimeout,
            @NotNull @PositiveDuration Duration readTimeout) {
        @AssertTrue(message = "adapter.openmetadata.base-url must be an absolute HTTP(S) URI")
        public boolean isBaseUrlValid() {
            return isHttpUri(baseUrl);
        }
    }

    private static boolean isHttpUri(URI value) {
        return value != null && value.isAbsolute()
                && ("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()));
    }

    @jakarta.validation.Constraint(validatedBy = PositiveDurationValidator.class)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,
            java.lang.annotation.ElementType.PARAMETER,
            java.lang.annotation.ElementType.ANNOTATION_TYPE})
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface PositiveDuration {
        String message() default "must be a positive duration";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    public static final class PositiveDurationValidator
            implements jakarta.validation.ConstraintValidator<PositiveDuration, Duration> {
        @Override
        public boolean isValid(Duration value, jakarta.validation.ConstraintValidatorContext context) {
            return value == null || (!value.isZero() && !value.isNegative());
        }
    }
}
