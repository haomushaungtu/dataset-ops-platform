package org.szah.dataset.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTests {
    private final AudienceValidator validator = new AudienceValidator("dataset-platform-api");

    @Test
    void acceptsRequiredAudienceAmongClientAudience() {
        assertThat(validator.validate(jwt(List.of("openmetadata-client", "dataset-platform-api"))).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsTokenForAnotherResource() {
        assertThat(validator.validate(jwt(List.of("openmetadata-client"))).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audience) {
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("user-1")
                .audience(audience)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
