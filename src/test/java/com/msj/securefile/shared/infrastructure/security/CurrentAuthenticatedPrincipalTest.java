package com.msj.securefile.shared.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentAuthenticatedPrincipalTest {

    private static final UUID USER_ID = UUID.fromString("3f1c9a52-0000-4000-8000-000000000001");

    private final CurrentAuthenticatedPrincipal current = new CurrentAuthenticatedPrincipal();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void get_isTheCallerOfAJwtAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        AuthenticatedPrincipal principal = current.get().orElseThrow();

        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.hasRole("ADMIN")).isTrue();
    }

    @Test
    void get_isEmptyWithoutAuthentication() {
        assertThat(current.get()).isEmpty();
    }

    @Test
    void get_isEmptyForTheAnonymousUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(current.get()).isEmpty();
    }
}
