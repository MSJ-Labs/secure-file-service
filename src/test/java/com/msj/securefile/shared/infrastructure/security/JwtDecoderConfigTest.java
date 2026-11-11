package com.msj.securefile.shared.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.BadJwtException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The decoder against a real JWKS endpoint (a local HTTP server standing for Keycloak). */
class JwtDecoderConfigTest {

    private static final String ISSUER = "http://localhost:8180/realms/secure-file";
    private static final String AUDIENCE = "secure-file-service";
    private static final String JWKS_PATH = "/protocol/openid-connect/certs";

    private static RSAKey signingKey;
    private static HttpServer keycloak;
    private static String jwksUri;

    @BeforeAll
    static void startKeycloak() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        keycloak.start();
        jwksUri = "http://localhost:" + keycloak.getAddress().getPort() + "/certs";
    }

    @AfterAll
    static void stopKeycloak() {
        keycloak.stop(0);
    }

    // The issuer URL is not reachable: only the explicit JWKS URI can serve the keys, which is the Docker case.
    private final JwtDecoder decoder = new JwtDecoderConfig().jwtDecoder(ISSUER, JWKS_PATH, jwksUri, AUDIENCE);

    @Test
    void decode_acceptsATokenOfTheRealmForThisApi() throws Exception {
        Jwt jwt = decoder.decode(token(ISSUER, List.of("account", AUDIENCE), signingKey, 300));

        assertThat(jwt.getSubject()).isEqualTo("3f1c9a52-0000-4000-8000-000000000001");
    }

    @Test
    void decode_rejectsATokenIssuedForAnotherAudience() throws Exception {
        String token = token(ISSUER, List.of("other-api"), signingKey, 300);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void decode_rejectsATokenFromAnotherIssuer() throws Exception {
        String token = token("http://evil/realms/secure-file", List.of(AUDIENCE), signingKey, 300);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void decode_rejectsAnExpiredToken() throws Exception {
        String token = token(ISSUER, List.of(AUDIENCE), signingKey, -3_600);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void decode_rejectsATokenSignedWithAnotherKey() throws Exception {
        RSAKey forger = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String token = token(ISSUER, List.of(AUDIENCE), forger, 300);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void decoder_startsWhileKeycloakIsDown() {
        // The keys are fetched on the first token, not at startup: the app can boot before the identity provider.
        JwtDecoder unreachable = new JwtDecoderConfig().jwtDecoder(ISSUER, JWKS_PATH, "http://localhost:1/certs", AUDIENCE);

        assertThat(unreachable).isNotNull();
    }

    private static String token(String issuer, List<String> audience, RSAKey key, long lifetimeSeconds) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("3f1c9a52-0000-4000-8000-000000000001")
                .issueTime(Date.from(now.minusSeconds(3_700)))
                .expirationTime(Date.from(now.plusSeconds(lifetimeSeconds)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
