package com.msj.securefile.auth.application.result;

import com.msj.securefile.auth.domain.user.User;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * What the use cases return about a user: a read model, so the web layer never touches the domain aggregate.
 */
public record UserProfile(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        String fullName,
        Set<String> roles,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
) {
    public static UserProfile from(User user) {
        return new UserProfile(
                user.getId().asString(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getFullName(),
                user.getRoles(),
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }
}