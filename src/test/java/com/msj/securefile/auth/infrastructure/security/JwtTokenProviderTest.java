package com.msj.securefile.auth.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.msj.securefile.auth.support.UserTestFactory.CLOCK;
import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hs512-minimum-512-bits-xxxxx";
    private static final Set<String> ROLES = Set.of("ROLE_USER");

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 900_000L, 604_800_000L, CLOCK);
    }

    @Test
    void generateAccessToken_isValid() {
        String token = provider.generateAccessToken("jdoe", ROLES);

        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("jdoe");
    }

    @Test
    void generateAccessToken_embedsRoles() {
        String token = provider.generateAccessToken("jdoe", Set.of("ROLE_USER", "ROLE_ADMIN"));

        assertThat(provider.getRolesFromToken(token)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void generateRefreshToken_isValid() {
        String token = provider.generateRefreshToken("jdoe", ROLES);

        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("jdoe");
    }

    @Test
    void getRolesFromToken_returnsEmptySetWhenNoRolesClaim() {
        // refresh token without roles claim (legacy / no roles)
        assertThat(provider.getRolesFromToken(provider.generateRefreshToken("jdoe", Set.of()))).isEmpty();
    }

    @Test
    void validateToken_returnsFalseForGarbage() {
        assertThat(provider.validateToken("not.a.token")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForExpiredToken() {
        String expiredToken = new JwtTokenProvider(SECRET, -1L, 604_800_000L, CLOCK)
                .generateAccessToken("jdoe", ROLES);

        assertThat(provider.validateToken(expiredToken)).isFalse();
    }

    @Test
    void accessAndRefreshTokens_areDifferent() {
        String access = provider.generateAccessToken("jdoe", ROLES);
        String refresh = provider.generateRefreshToken("jdoe", ROLES);

        assertThat(access).isNotEqualTo(refresh);
    }
}