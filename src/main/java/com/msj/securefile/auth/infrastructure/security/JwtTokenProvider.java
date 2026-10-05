package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.domain.user.UserId;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class JwtTokenProvider implements TokenService {

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_USER_ID = "uid";

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final Clock clock;

    public JwtTokenProvider(@Value("${jwt.secret}") String jwtSecret,
                            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
                            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
                            Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.clock = clock;
    }

    @Override
    public String generateAccessToken(UserId userId, String username, Set<String> roles) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId.asString())
                .claim("type", "access")
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    @Override
    public String generateRefreshToken(UserId userId, String username, Set<String> roles) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId.asString())
                .claim("type", "refresh")
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(refreshTokenExpirationMs)))
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    @Override
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    @Override
    public Optional<UserId> getUserIdFromToken(String token) {
        return Optional.ofNullable(parseClaims(token).get(CLAIM_USER_ID, String.class)).map(UserId::of);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getRolesFromToken(String token) {
        List<String> roles = (List<String>) parseClaims(token).get(CLAIM_ROLES);
        return roles != null ? Set.copyOf(roles) : Set.of();
    }

    @Override
    public LocalDateTime getExpirationFromToken(String token) {
        return parseClaims(token).getExpiration().toInstant()
                .atZone(ZoneOffset.UTC).toLocalDateTime();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}