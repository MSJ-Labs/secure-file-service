package com.msj.securefile.auth.support;

import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Central factory for building test User instances.
 * Use static imports in tests for readability. All times derive from the fixed {@link #NOW}.
 */
public final class UserTestFactory {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 12, 0);
    public static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private UserTestFactory() {}

    public static User activeUser() {
        return activeUser("jdoe");
    }

    public static User activeUser(String username) {
        return stored(username, true, true, 0, null, Set.of("ROLE_USER"));
    }

    public static User disabledUser(String username) {
        return stored(username, false, true, 0, null, Set.of("ROLE_USER"));
    }

    public static User userWithoutRoles(String username) {
        return stored(username, true, true, 0, null, null);
    }

    public static User lockedUser() {
        return lockedUser("locked");
    }

    public static User lockedUser(String username) {
        return stored(username, true, false, 5, NOW.plusMinutes(25), Set.of("ROLE_USER"));
    }

    public static User expiredLockUser(String username) {
        return stored(username, true, false, 5, NOW.minusMinutes(1), Set.of("ROLE_USER"));
    }

    private static User stored(String username, boolean enabled, boolean accountNonLocked,
                               int failedLoginAttempts, LocalDateTime lockedUntil, Set<String> roles) {
        return User.reconstitute(
                UserId.generate(), username, username + "@example.com", "$hashed$", null, null,
                enabled, true, accountNonLocked, true,
                NOW, NOW, null, failedLoginAttempts, lockedUntil, roles);
    }
}