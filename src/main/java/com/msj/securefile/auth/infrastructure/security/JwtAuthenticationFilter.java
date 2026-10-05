package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.domain.user.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService jwtTokenProvider;
    private final JwtCookieService cookieService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            // A token without the user id (issued before it was carried) cannot say who the caller is: no authentication.
            jwtTokenProvider.getUserIdFromToken(token).ifPresent(userId -> authenticate(request, token, userId));
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token, UserId userId) {
        String username = jwtTokenProvider.getUsernameFromToken(token);
        Set<String> roles = jwtTokenProvider.getRolesFromToken(token);

        UserPrincipal principal = new UserPrincipal(userId, username, roles);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String resolveToken(HttpServletRequest request) {
        // 1. httpOnly cookie (preferred — secure)
        String fromCookie = cookieService.extractAccessToken(request);
        if (StringUtils.hasText(fromCookie)) return fromCookie;

        // 2. Authorization header fallback (Swagger/Postman in dev)
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }

        return null;
    }
}