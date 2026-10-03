package com.msj.securefile.auth.domain.user;

import org.junit.jupiter.api.Test;

import static com.msj.securefile.auth.support.UserTestFactory.NOW;
import static com.msj.securefile.auth.support.UserTestFactory.expiredLockUser;
import static com.msj.securefile.auth.support.UserTestFactory.lockedUser;
import static org.assertj.core.api.Assertions.*;

class UserTest {

    private static User register(String username, String email, String hash, String first, String last) {
        return User.register(username, email, hash, first, last, NOW);
    }

    @Test
    void register_createsEnabledUser() {
        User user = register("jdoe", "j@doe.com", "hashed", "John", "Doe");

        assertThat(user.getUsername()).isEqualTo("jdoe");
        assertThat(user.getEmail()).isEqualTo("j@doe.com");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getRoles()).containsExactly("ROLE_USER");
        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void register_normalizesEmailToLowerCase() {
        User user = register("jdoe", "J@DOE.COM", "hashed", null, null);
        assertThat(user.getEmail()).isEqualTo("j@doe.com");
    }

    @Test
    void register_rejectsBlankUsername() {
        assertThatThrownBy(() -> register("  ", "j@doe.com", "hashed", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_rejectsBlankEmail() {
        assertThatThrownBy(() -> register("jdoe", "", "hashed", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_rejectsBlankPassword() {
        assertThatThrownBy(() -> register("jdoe", "j@doe.com", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordSuccessfulLogin_resetsFailedAttemptsAndUnlocks() {
        User user = register("jdoe", "j@doe.com", "hashed", null, null);
        user.recordFailedLoginAttempt(NOW);
        user.recordFailedLoginAttempt(NOW);

        user.recordSuccessfulLogin(NOW.plusMinutes(1));

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void recordFailedLoginAttempt_incrementsCounter() {
        User user = register("jdoe", "j@doe.com", "hashed", null, null);

        user.recordFailedLoginAttempt(NOW);
        user.recordFailedLoginAttempt(NOW);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
        assertThat(user.isAccountNonLocked()).isTrue();
    }

    @Test
    void recordFailedLoginAttempt_locksAccountAtFiveAttempts() {
        User user = register("jdoe", "j@doe.com", "hashed", null, null);

        for (int i = 0; i < 5; i++) {
            user.recordFailedLoginAttempt(NOW);
        }

        assertThat(user.isAccountNonLocked()).isFalse();
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusMinutes(30));
    }

    @Test
    void recordFailedLoginAttempt_afterTheLockExpired_startsANewSeries() {
        User user = expiredLockUser("jdoe");

        user.recordFailedLoginAttempt(NOW);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void isLockedAt_returnsFalseForUnlockedUser() {
        User user = register("jdoe", "j@doe.com", "hashed", null, null);
        assertThat(user.isLockedAt(NOW)).isFalse();
    }

    @Test
    void isLockedAt_returnsTrueWhileTheLockPeriodRuns() {
        assertThat(lockedUser("jdoe").isLockedAt(NOW)).isTrue();
    }

    @Test
    void isLockedAt_returnsFalseOnceTheLockPeriodHasPassed_withoutChangingTheUser() {
        User user = expiredLockUser("jdoe");

        assertThat(user.isLockedAt(NOW)).isFalse();
        assertThat(user.isAccountNonLocked()).isFalse();
        assertThat(user.getLockedUntil()).isNotNull();
    }

    @Test
    void changePassword_updatesHash() {
        User user = register("jdoe", "j@doe.com", "old-hash", null, null);

        user.changePassword("new-hash", NOW.plusMinutes(5));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.isCredentialsNonExpired()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void changePassword_rejectsBlank() {
        User user = register("jdoe", "j@doe.com", "hashed", null, null);

        assertThatThrownBy(() -> user.changePassword("  ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getFullName_returnsCorrectFormat() {
        User both = register("jdoe", "j@doe.com", "h", "John", "Doe");
        assertThat(both.getFullName()).isEqualTo("John Doe");

        User firstOnly = register("jdoe2", "j2@doe.com", "h", "John", null);
        assertThat(firstOnly.getFullName()).isEqualTo("John");

        User lastOnly = register("jdoe3", "j3@doe.com", "h", null, "Doe");
        assertThat(lastOnly.getFullName()).isEqualTo("Doe");

        User neitherName = register("jdoe4", "j4@doe.com", "h", null, null);
        assertThat(neitherName.getFullName()).isEqualTo("jdoe4");
    }
}