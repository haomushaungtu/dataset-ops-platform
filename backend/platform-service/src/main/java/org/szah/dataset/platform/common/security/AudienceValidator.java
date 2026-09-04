package org.szah.dataset.platform.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final String requiredAudience;

    AudienceValidator(String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()) {
            throw new IllegalArgumentException("required audience must not be blank");
        }
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience().contains(requiredAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Required audience is missing", null));
    }
}
