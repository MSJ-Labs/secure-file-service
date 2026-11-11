package com.msj.securefile.shared.infrastructure.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, free of Spring Security types so that application and domain code can use it.
 * {@link #userId()} is the Keycloak {@code sub}: each bounded context wraps it in its own value object.
 */
public interface AuthenticatedPrincipal {

    UUID userId();

    /** Application roles without the {@code ROLE_} prefix (e.g. {@code USER}, {@code ADMIN}). */
    Set<String> roles();

    default boolean hasRole(String role) {
        return roles().contains(role);
    }
}
