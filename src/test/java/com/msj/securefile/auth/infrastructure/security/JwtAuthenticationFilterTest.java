package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.domain.user.UserId;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final UserId USER_ID = UserId.of(42L);

    @Mock private TokenService jwtTokenProvider;
    @Mock private JwtCookieService cookieService;
    @Mock private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenInCookie_setsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(cookieService.extractAccessToken(request)).thenReturn("valid-token");
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(Optional.of(USER_ID));
        when(jwtTokenProvider.getUsernameFromToken("valid-token")).thenReturn("jdoe");
        when(jwtTokenProvider.getRolesFromToken("valid-token")).thenReturn(Set.of("ROLE_USER"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("jdoe");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(UserPrincipal.class,
                        principal -> assertThat(principal.getUserId()).isEqualTo(USER_ID));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void validTokenInBearerHeader_setsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(cookieService.extractAccessToken(request)).thenReturn(null);
        when(jwtTokenProvider.validateToken("header-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("header-token")).thenReturn(Optional.of(USER_ID));
        when(jwtTokenProvider.getUsernameFromToken("header-token")).thenReturn("jdoe");
        when(jwtTokenProvider.getRolesFromToken("header-token")).thenReturn(Set.of("ROLE_USER"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void validTokenWithoutUserId_doesNotSetAuthentication() throws Exception {
        // Issued before the id was carried: the signature is fine, but nothing can say who the caller is, so it
        // must not authenticate (a file endpoint would have no owner to check against).
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(cookieService.extractAccessToken(request)).thenReturn("legacy-token");
        when(jwtTokenProvider.validateToken("legacy-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("legacy-token")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void invalidToken_doesNotSetAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(cookieService.extractAccessToken(request)).thenReturn("bad-token");
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void noToken_doesNotSetAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(cookieService.extractAccessToken(request)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}