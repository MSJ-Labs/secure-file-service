package com.msj.securefile.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("secure-file-service");

    @Test
    void validate_succeedsWhenAudienceContainsExpectedValue() {
        OAuth2TokenValidatorResult result = validator.validate(jwtBuilder().audience(java.util.List.of("account", "secure-file-service")).build());

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_failsWhenAudienceIsAnotherService() {
        OAuth2TokenValidatorResult result = validator.validate(jwtBuilder().audience(java.util.List.of("other-api")).build());

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> "invalid_token".equals(e.getErrorCode()));
    }

    @Test
    void validate_failsWhenAudienceIsMissing() {
        OAuth2TokenValidatorResult result = validator.validate(jwtBuilder().build());

        assertThat(result.hasErrors()).isTrue();
    }

    private Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-id")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }
}