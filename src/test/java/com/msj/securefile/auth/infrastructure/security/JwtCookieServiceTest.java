package com.msj.securefile.auth.infrastructure.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JwtCookieServiceTest {

    private JwtCookieService cookieService;

    @BeforeEach
    void setUp() {
        cookieService = new JwtCookieService();
        ReflectionTestUtils.setField(cookieService, "secure", false);
        ReflectionTestUtils.setField(cookieService, "domain", "");
        ReflectionTestUtils.setField(cookieService, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(cookieService, "refreshTokenExpirationMs", 604_800_000L);
    }

    @Test
    void addAuthCookies_setsHttpOnlyCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.addAuthCookies(response, "access-tok", "refresh-tok");

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anyMatch(c -> c.contains("access_token=access-tok") && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
        assertThat(cookies).anyMatch(c -> c.contains("refresh_token=refresh-tok") && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
    }

    @Test
    void extractAccessToken_returnsTokenFromCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", "my-token"), new Cookie("other", "value"));

        assertThat(cookieService.extractAccessToken(request)).isEqualTo("my-token");
    }

    @Test
    void extractAccessToken_returnsNullWhenNoCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(cookieService.extractAccessToken(request)).isNull();
    }

    @Test
    void clearAuthCookies_setsMaxAgeZero() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.clearAuthCookies(response);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).anyMatch(c -> c.contains("access_token=") && c.contains("Max-Age=0"));
        assertThat(cookies).anyMatch(c -> c.contains("refresh_token=") && c.contains("Max-Age=0"));
    }
}