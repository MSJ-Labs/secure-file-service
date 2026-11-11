package com.msj.securefile.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAuthenticatedPrincipalTest {

    private static final UUID USER_ID = UUID.fromString("3f1c9a52-0000-4000-8000-000000000001");

    @Test
    void from_extractsUserIdAndRolesWithoutPrefix() {
        JwtAuthenticationToken auth = token(USER_ID.toString(), "ROLE_USER", "ROLE_ADMIN");

        JwtAuthenticatedPrincipal principal = JwtAuthenticatedPrincipal.from(auth);

        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void hasRole_reflectsRoles() {
        JwtAuthenticatedPrincipal principal = JwtAuthenticatedPrincipal.from(token(USER_ID.toString(), "ROLE_USER"));

        assertThat(principal.hasRole("USER")).isTrue();
        assertThat(principal.hasRole("ADMIN")).isFalse();
    }

    @Test
    void from_ignoresAuthoritiesWithoutRolePrefix() {
        JwtAuthenticatedPrincipal principal = JwtAuthenticatedPrincipal.from(token(USER_ID.toString(), "SCOPE_openid"));

        assertThat(principal.roles()).isEmpty();
    }

    @Test
    void from_rejectsSubjectThatIsNotAUuid() {
        JwtAuthenticationToken auth = token("not-a-uuid");

        assertThatThrownBy(() -> JwtAuthenticatedPrincipal.from(auth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_rejectsMissingSubject() {
        JwtAuthenticationToken auth = token(null);

        assertThatThrownBy(() -> JwtAuthenticatedPrincipal.from(auth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JwtAuthenticationToken token(String subject, String... authorities) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", "openid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (subject != null) {
            builder.subject(subject);
        }
        return new JwtAuthenticationToken(builder.build(),
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }
}