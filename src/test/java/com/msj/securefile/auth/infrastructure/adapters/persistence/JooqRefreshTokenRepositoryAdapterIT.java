package com.msj.securefile.auth.infrastructure.adapters.persistence;

import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;
import com.msj.securefile.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JooqRefreshTokenRepositoryAdapterIT {

    private final Clock clock = Clock.systemUTC();
    private final JooqUserRepositoryAdapter users = new JooqUserRepositoryAdapter(PostgresTestDatabase.dsl(), clock);
    private final JooqRefreshTokenRepositoryAdapter tokens = new JooqRefreshTokenRepositoryAdapter(PostgresTestDatabase.dsl(), clock);

    private UserId owner;

    @BeforeEach
    void createTokenOwner() {
        PostgresTestDatabase.deleteAllAccounts();
        owner = saveUser("owner");
    }

    private UserId saveUser(String username) {
        return users.save(User.register(username, username + "@example.com", "hashed-password", null, null, LocalDateTime.now(clock))).getId();
    }

    private LocalDateTime inOneHour() {
        return LocalDateTime.now(clock).plusHours(1);
    }

    @Test
    void savedToken_isValid() {
        tokens.save("hash-1", owner, inOneHour());

        assertThat(tokens.isValid("hash-1")).isTrue();
    }

    @Test
    void unknownToken_isNotValid() {
        assertThat(tokens.isValid("never-saved")).isFalse();
    }

    @Test
    void expiredToken_isNotValid() {
        tokens.save("hash-expired", owner, LocalDateTime.now(clock).minusMinutes(1));

        assertThat(tokens.isValid("hash-expired")).isFalse();
    }

    @Test
    void revokedToken_isNotValid() {
        tokens.save("hash-2", owner, inOneHour());

        tokens.revoke("hash-2");

        assertThat(tokens.isValid("hash-2")).isFalse();
    }

    @Test
    void revokeAllByUserId_revokesEveryTokenOfThatUserOnly() {
        UserId other = saveUser("other");
        tokens.save("owner-1", owner, inOneHour());
        tokens.save("owner-2", owner, inOneHour());
        tokens.save("other-1", other, inOneHour());

        tokens.revokeAllByUserId(owner);

        assertThat(tokens.isValid("owner-1")).isFalse();
        assertThat(tokens.isValid("owner-2")).isFalse();
        assertThat(tokens.isValid("other-1")).isTrue();
    }
}