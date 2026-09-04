package org.szah.dataset.integrations.openmetadata.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterPropertiesTests {
    @Test
    void rejectsRelativeUrisAndEmptySecrets() {
        var properties = new AdapterProperties(
                new AdapterProperties.Security(URI.create("relative"), "audience"),
                new AdapterProperties.Iam(URI.create("token"), "client", "", "scope"),
                new AdapterProperties.OpenMetadata(URI.create("metadata"), Duration.ZERO, Duration.ofSeconds(1)));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("security.issuerValid", "iam.tokenUriValid", "iam.clientSecret",
                            "openmetadata.baseUrlValid", "openmetadata.connectTimeout");
        }
    }
}
