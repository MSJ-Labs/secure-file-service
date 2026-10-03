package com.msj.securefile.auth.domain.user;

import com.msj.securefile.shared.domain.AggregateRoot;
import lombok.Getter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * User aggregate root — pure domain object, zero Spring/infrastructure dependencies.
 * Business rules live here: account locking, login tracking.
 */
@Getter
public class User extends AggregateRoot<UserId> {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final String username;
    private final String email;
    private String passwordHash;
    private final String firstName;
    private final String lastName;
    private final boolean enabled;
    private final boolean accountNonExpired;
    private boolean accountNonLocked;
    private boolean credentialsNonExpired;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;
    private int failedLoginAttempts;
    private LocalDateTime lockedUntil;

    // Role names (e.g. "ROLE_USER", "ROLE_ADMIN")
    private Set<String> roles;

    private User(UserId id, String username, String email, String passwordHash, String firstName, String lastName,
                 boolean enabled, boolean accountNonExpired, boolean accountNonLocked, boolean credentialsNonExpired,
                 LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime lastLoginAt,
                 int failedLoginAttempts, LocalDateTime lockedUntil, Set<String> roles) {
        super(id);
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.enabled = enabled;
        this.accountNonExpired = accountNonExpired;
        this.accountNonLocked = accountNonLocked;
        this.credentialsNonExpired = credentialsNonExpired;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockedUntil = lockedUntil;
        this.roles = roles;
    }

    public static User register(String username, String email, String passwordHash,
                                String firstName, String lastName, LocalDateTime now) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("Password is required");

        return new User(
                UserId.generate(),
                username.trim(),
                email.trim().toLowerCase(),
                passwordHash,
                firstName != null ? firstName.trim() : null,
                lastName != null ? lastName.trim() : null,
                true, true, true, true,
                now, now, null,
                0, null,
                Set.of("ROLE_USER"));
    }

    /**
     * Rebuilds a stored user: no rule is applied, the persisted state is trusted as is.
     */
    public static User reconstitute(UserId id, String username, String email, String passwordHash,
                                    String firstName, String lastName,
                                    boolean enabled, boolean accountNonExpired, boolean accountNonLocked,
                                    boolean credentialsNonExpired,
                                    LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime lastLoginAt,
                                    int failedLoginAttempts, LocalDateTime lockedUntil, Set<String> roles) {
        return new User(id, username, email, passwordHash, firstName, lastName,
                enabled, accountNonExpired, accountNonLocked, credentialsNonExpired,
                createdAt, updatedAt, lastLoginAt, failedLoginAttempts, lockedUntil, roles);
    }

    public UserId getId() {
        return id();
    }

    public void recordSuccessfulLogin(LocalDateTime now) {
        this.lastLoginAt = now;
        this.failedLoginAttempts = 0;
        this.accountNonLocked = true;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public void recordFailedLoginAttempt(LocalDateTime now) {
        // A lock that has run out starts a fresh series of attempts.
        if (lockExpiredAt(now)) {
            this.failedLoginAttempts = 0;
            this.accountNonLocked = true;
            this.lockedUntil = null;
        }
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
            this.accountNonLocked = false;
            this.lockedUntil = now.plus(LOCK_DURATION);
        }
        this.updatedAt = now;
    }

    /**
     * Pure check: the account is locked until the lock period has passed.
     */
    public boolean isLockedAt(LocalDateTime now) {
        return !accountNonLocked && !lockExpiredAt(now);
    }

    private boolean lockExpiredAt(LocalDateTime now) {
        return lockedUntil != null && now.isAfter(lockedUntil);
    }

    public void changePassword(String newPasswordHash, LocalDateTime now) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) throw new IllegalArgumentException("Password is required");
        this.passwordHash = newPasswordHash;
        this.credentialsNonExpired = true;
        this.updatedAt = now;
    }

    public void replaceRoles(Set<String> roles) {
        this.roles = Set.copyOf(roles);
    }

    public String getFullName() {
        if (firstName != null && lastName != null) return firstName + " " + lastName;
        if (firstName != null) return firstName;
        if (lastName != null) return lastName;
        return username;
    }
}