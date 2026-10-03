package com.msj.securefile.auth.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Manages httpOnly JWT cookies.
 * Access token: 15 min. Refresh token: 7 days.
 * SameSite=Strict prevents CSRF — no need for CSRF tokens with stateless JWT.
 */
@Slf4j
@Component
public class JwtCookieService {

    private static final String ACCESS_COOKIE = "access_token";
    private static final String REFRESH_COOKIE = "refresh_token";

    @Value("${app.security.cookie.secure:false}")
    private boolean secure;

    @Value("${app.security.cookie.domain:}")
    private String domain;

    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public void addAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,buildCookie(ACCESS_COOKIE, accessToken, accessTokenExpirationMs / 1000).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,buildCookie(REFRESH_COOKIE, refreshToken, refreshTokenExpirationMs / 1000).toString());
    }

    public void refreshAccessTokenCookie(HttpServletResponse response, String accessToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,buildCookie(ACCESS_COOKIE, accessToken, accessTokenExpirationMs / 1000).toString());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,buildCookie(ACCESS_COOKIE, "", 0).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,buildCookie(REFRESH_COOKIE, "", 0).toString());
    }

    public String extractAccessToken(jakarta.servlet.http.HttpServletRequest request) {
        return extractCookie(request, ACCESS_COOKIE);
    }

    public String extractRefreshToken(jakarta.servlet.http.HttpServletRequest request) {
        return extractCookie(request, REFRESH_COOKIE);
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds);

        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }

        return builder.build();
    }

    private String extractCookie(jakarta.servlet.http.HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}