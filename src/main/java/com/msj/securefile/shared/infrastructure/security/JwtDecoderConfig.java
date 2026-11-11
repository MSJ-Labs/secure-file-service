package com.msj.securefile.shared.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
class JwtDecoderConfig {

    /**
     * Keycloak publishes its signing keys at a fixed path under the issuer. Using it directly (instead of
     * OIDC discovery) makes the keys load lazily: the app can start while Keycloak is down.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.security.oauth2.issuer-uri}") String issuerUri,
            @Value("${app.security.oauth2.jwks-path}") String jwksPath,
            @Value("${app.security.oauth2.jwks-uri:}") String jwksUri,
            @Value("${app.security.oauth2.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri.isBlank() ? issuerUri + jwksPath : jwksUri).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}