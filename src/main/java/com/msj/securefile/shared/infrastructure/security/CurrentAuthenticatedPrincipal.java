package com.msj.securefile.shared.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one place that reads the caller from the security context. Bounded contexts use it in their own adapters to
 * translate the caller into their own types; anonymous callers give an empty result.
 */
@Component
public class CurrentAuthenticatedPrincipal {

    public Optional<AuthenticatedPrincipal> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.of(JwtAuthenticatedPrincipal.from(jwtAuthentication));
        }
        return Optional.empty();
    }
}