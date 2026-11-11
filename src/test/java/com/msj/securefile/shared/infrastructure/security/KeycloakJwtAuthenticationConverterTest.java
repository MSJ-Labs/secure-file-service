package com.msj.securefile.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void convert_mapsRealmRolesToRoleAuthorities() {
        Jwt jwt = jwtBuilder().claim("realm_access", Map.of("roles", List.of("USER", "ADMIN"))).build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void convert_ignoresKeycloakBuiltInRoles() {
        Jwt jwt = jwtBuilder().claim("realm_access", Map.of("roles",
                List.of("default-roles-secure-file", "offline_access", "uma_authorization", "USER"))).build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void convert_returnsNoAuthorityWhenRealmAccessIsMissing() {
        AbstractAuthenticationToken auth = converter.convert(jwtBuilder().build());

        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void convert_usesSubjectAsPrincipalName() {
        AbstractAuthenticationToken auth = converter.convert(jwtBuilder().build());

        assertThat(auth.getName()).isEqualTo("3f1c9a52-0000-4000-8000-000000000001");
    }

    private Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f1c9a52-0000-4000-8000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }
}