package com.msj.securefile.auth.infrastructure.adapters.persistence;

import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;
import com.msj.securefile.support.PostgresTestDatabase;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.auth.Tables.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class JooqUserRepositoryAdapterIT {

    private final Clock clock = Clock.systemUTC();
    private final JooqUserRepositoryAdapter repository = new JooqUserRepositoryAdapter(PostgresTestDatabase.dsl(), clock);

    @BeforeEach
    void cleanDatabase() {
        PostgresTestDatabase.deleteAllAccounts();
    }

    private User newUser(String username) {
        return User.register(username, username + "@example.com", "hashed-password", "Jane", "Doe",
                LocalDateTime.now(clock));
    }

    @Test
    void save_thenFindByUsername_returnsTheUserWithItsDefaultRole() {
        User saved = repository.save(newUser("alice"));

        User found = repository.findByUsername("alice").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmail()).isEqualTo("alice@example.com");
        assertThat(found.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(found.getFirstName()).isEqualTo("Jane");
        assertThat(found.getLastName()).isEqualTo("Doe");
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getRoles()).containsExactly("ROLE_USER");
        // PostgreSQL keeps microseconds
        assertThat(found.getCreatedAt()).isCloseTo(saved.getCreatedAt(), within(1, ChronoUnit.MILLIS));
    }

    @Test
    void findByEmailAndFindById_returnTheSameUser() {
        User saved = repository.save(newUser("bob"));

        assertThat(repository.findByEmail("bob@example.com")).get().extracting(User::getId).isEqualTo(saved.getId());
        assertThat(repository.findById(saved.getId())).get().extracting(User::getUsername).isEqualTo("bob");
    }

    @Test
    void find_returnsEmptyWhenTheUserDoesNotExist() {
        assertThat(repository.findByUsername("nobody")).isEmpty();
        assertThat(repository.findByEmail("nobody@example.com")).isEmpty();
        assertThat(repository.findById(UserId.generate())).isEmpty();
    }

    @Test
    void exists_reflectsWhatWasSaved() {
        repository.save(newUser("carol"));

        assertThat(repository.existsByUsername("carol")).isTrue();
        assertThat(repository.existsByEmail("carol@example.com")).isTrue();
        assertThat(repository.existsByUsername("dave")).isFalse();
        assertThat(repository.existsByEmail("dave@example.com")).isFalse();
    }

    @Test
    void save_existingUser_updatesItsStateWithoutDuplicatingTheRole() {
        User user = repository.save(newUser("erin"));
        for (int i = 0; i < 5; i++) {
            user.recordFailedLoginAttempt(LocalDateTime.now(clock));
        }

        repository.save(user);
        User reloaded = repository.findByUsername("erin").orElseThrow();

        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(reloaded.isAccountNonLocked()).isFalse();
        assertThat(reloaded.getLockedUntil()).isNotNull();
        assertThat(reloaded.getRoles()).containsExactly("ROLE_USER");
        assertThat(PostgresTestDatabase.dsl().fetchCount(USER_ROLE)).isEqualTo(1);
    }

    @Test
    void save_rejectsASecondAccountWithTheSameUsername() {
        repository.save(newUser("frank"));
        User duplicate = User.register("frank", "other@example.com", "hashed-password", null, null,
                LocalDateTime.now(clock));

        assertThatThrownBy(() -> repository.save(duplicate)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void save_persistsTheRolesOfTheUser_andDropsTheOnesThatAreGone() {
        User user = newUser("gina");
        user.replaceRoles(Set.of("ROLE_USER", "ROLE_ADMIN"));
        repository.save(user);
        assertThat(repository.findByUsername("gina").orElseThrow().getRoles())
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        user.replaceRoles(Set.of("ROLE_ADMIN"));
        repository.save(user);

        assertThat(repository.findByUsername("gina").orElseThrow().getRoles()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void save_rejectsAnUnknownRole() {
        User user = newUser("hank");
        user.replaceRoles(Set.of("ROLE_DOES_NOT_EXIST"));

        assertThatThrownBy(() -> repository.save(user)).isInstanceOf(IllegalArgumentException.class);
    }
}