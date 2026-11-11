package com.msj.securefile.shared.infrastructure.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

record JwtAuthenticatedPrincipal(UUID userId, Set<String> roles) implements AuthenticatedPrincipal {

    private static final String ROLE_PREFIX = "ROLE_";

    static JwtAuthenticatedPrincipal from(JwtAuthenticationToken authentication) {
        String subject = authentication.getToken().getSubject();
        if (subject == null) {
            throw new IllegalArgumentException("Token has no subject");
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        return new JwtAuthenticatedPrincipal(UUID.fromString(subject), roles);
    }
}