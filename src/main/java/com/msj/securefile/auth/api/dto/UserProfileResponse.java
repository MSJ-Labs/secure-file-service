package com.msj.securefile.auth.api.dto;

import com.msj.securefile.auth.application.result.UserProfile;

import java.time.LocalDateTime;
import java.util.Set;

public record UserProfileResponse(
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
    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(
                profile.id(),
                profile.username(),
                profile.email(),
                profile.firstName(),
                profile.lastName(),
                profile.fullName(),
                profile.roles(),
                profile.createdAt(),
                profile.lastLoginAt()
        );
    }
}