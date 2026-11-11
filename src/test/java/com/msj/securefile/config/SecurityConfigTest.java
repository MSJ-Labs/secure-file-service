package com.msj.securefile.config;

import com.msj.securefile.shared.infrastructure.security.CurrentAuthenticatedPrincipal;
import com.msj.securefile.shared.infrastructure.security.KeycloakJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class, properties = "app.security.oauth2.issuer-uri=http://issuer")
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverter.class, CurrentAuthenticatedPrincipal.class,
        SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

    private static final String USER_ID = "3f1c9a52-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void publicRoutes_areReachableWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health/probe")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/probe")).andExpect(status().isOk());
    }

    @Test
    void protectedRoute_returns401WithoutToken() throws Exception {
        mockMvc.perform(get("/probe/secured")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRoute_returns401WhenTheDecoderRejectsTheToken() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("bad audience"));

        mockMvc.perform(get("/probe/secured").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRoute_ignoresTheOldCookieAuthentication() throws Exception {
        // The access_token cookie of the former login must not authenticate anything any more.
        mockMvc.perform(get("/probe/secured").cookie(new jakarta.servlet.http.Cookie("access_token", "whatever")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRoute_acceptsAValidToken() throws Exception {
        tokenWithRoles("user-token", "USER");

        mockMvc.perform(get("/probe/secured").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());
    }

    @Test
    void adminRoute_returns403ForTheUserRole() throws Exception {
        tokenWithRoles("user-token", "USER");

        mockMvc.perform(get("/probe/admin").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoute_isAllowedForTheAdminRole() throws Exception {
        tokenWithRoles("admin-token", "USER", "ADMIN");

        mockMvc.perform(get("/probe/admin").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void theCallerIsTheTokenSubject() throws Exception {
        tokenWithRoles("user-token", "USER");

        mockMvc.perform(get("/probe/me").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(USER_ID));
    }

    private void tokenWithRoles(String tokenValue, String... roles) {
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(USER_ID)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(tokenValue)).thenReturn(jwt);
    }

    @RestController
    static class ProbeController {

        private final CurrentAuthenticatedPrincipal currentPrincipal;

        ProbeController(CurrentAuthenticatedPrincipal currentPrincipal) {
            this.currentPrincipal = currentPrincipal;
        }

        @GetMapping("/actuator/health/probe")
        String health() {
            return "up";
        }

        @GetMapping("/v3/api-docs/probe")
        String apiDocs() {
            return "docs";
        }

        @GetMapping("/probe/secured")
        String secured() {
            return "secured";
        }

        @GetMapping("/probe/admin")
        @PreAuthorize("hasRole('ADMIN')")
        String admin() {
            return "admin";
        }

        @GetMapping("/probe/me")
        String me() {
            return currentPrincipal.get().orElseThrow().userId().toString();
        }
    }
}
