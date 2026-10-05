package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.domain.user.UserId;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static com.msj.securefile.auth.support.UserTestFactory.CLOCK;
import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hs512-minimum-512-bits-xxxxx";
    private static final Set<String> ROLES = Set.of("ROLE_USER");
    private static final UserId USER_ID = UserId.of(42L);

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 900_000L, 604_800_000L, CLOCK);
    }

    @Test
    void generateAccessToken_isValid() {
        String token = provider.generateAccessToken(USER_ID, "jdoe", ROLES);

        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("jdoe");
    }

    @Test
    void generateAccessToken_embedsRoles() {
        String token = provider.generateAccessToken(USER_ID, "jdoe", Set.of("ROLE_USER", "ROLE_ADMIN"));

        assertThat(provider.getRolesFromToken(token)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void generateAccessToken_embedsTheUserId() {
        String token = provider.generateAccessToken(USER_ID, "jdoe", ROLES);

        assertThat(provider.getUserIdFromToken(token)).contains(USER_ID);
    }

    @Test
    void generateRefreshToken_isValid() {
        String token = provider.generateRefreshToken(USER_ID, "jdoe", ROLES);

        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("jdoe");
    }

    @Test
    void generateRefreshToken_embedsTheUserId() {
        String token = provider.generateRefreshToken(USER_ID, "jdoe", ROLES);

        assertThat(provider.getUserIdFromToken(token)).contains(USER_ID);
    }

    @Test
    void getUserIdFromToken_isEmptyWhenTheTokenHasNoUserIdClaim() {
        // A token issued before the claim existed: valid, but it cannot say who the caller is.
        String legacyToken = Jwts.builder()
                .subject("jdoe")
                .claim("roles", List.of("ROLE_USER"))
                .expiration(Date.from(CLOCK.instant().plusSeconds(3_600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512)
                .compact();

        assertThat(provider.validateToken(legacyToken)).isTrue();
        assertThat(provider.getUserIdFromToken(legacyToken)).isEmpty();
    }

    @Test
    void getRolesFromToken_returnsEmptySetWhenNoRolesClaim() {
        // refresh token without roles claim (legacy / no roles)
        assertThat(provider.getRolesFromToken(provider.generateRefreshToken(USER_ID, "jdoe", Set.of()))).isEmpty();
    }

    @Test
    void validateToken_returnsFalseForGarbage() {
        assertThat(provider.validateToken("not.a.token")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForExpiredToken() {
        String expiredToken = new JwtTokenProvider(SECRET, -1L, 604_800_000L, CLOCK)
                .generateAccessToken(USER_ID, "jdoe", ROLES);

        assertThat(provider.validateToken(expiredToken)).isFalse();
    }

    @Test
    void accessAndRefreshTokens_areDifferent() {
        String access = provider.generateAccessToken(USER_ID, "jdoe", ROLES);
        String refresh = provider.generateRefreshToken(USER_ID, "jdoe", ROLES);

        assertThat(access).isNotEqualTo(refresh);
    }
}