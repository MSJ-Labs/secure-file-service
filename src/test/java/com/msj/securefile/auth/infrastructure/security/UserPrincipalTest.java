package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;
import com.msj.securefile.shared.infrastructure.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

import static com.msj.securefile.auth.support.UserTestFactory.activeUser;
import static com.msj.securefile.auth.support.UserTestFactory.disabledUser;
import static com.msj.securefile.auth.support.UserTestFactory.userWithoutRoles;
import static org.assertj.core.api.Assertions.assertThat;

class UserPrincipalTest {

    private static final UserId USER_ID = UserId.of(42L);

    @Test
    void wrapsUserDetailsCorrectly() {
        User user = activeUser("jdoe");
        UserPrincipal principal = new UserPrincipal(user);

        assertThat(principal.getUsername()).isEqualTo("jdoe");
        assertThat(principal.getPassword()).isEqualTo("$hashed$");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
        assertThat(principal.getUser()).isSameAs(user);
    }

    @Test
    void isTheSharedAuthenticatedPrincipalOtherContextsRead() {
        // Other contexts read the id through the shared contract, without knowing the auth types.
        AuthenticatedPrincipal principal = new UserPrincipal(USER_ID, "jdoe", Set.of("ROLE_USER"));

        assertThat(principal.id()).isEqualTo(USER_ID.value().toLong());
    }

    @Test
    void wrappedUser_exposesItsId() {
        User user = activeUser("jdoe");

        assertThat(new UserPrincipal(user).getUserId()).isEqualTo(user.getId());
    }

    @Test
    void mapsRolesToGrantedAuthorities() {
        User user = activeUser("jdoe");
        UserPrincipal principal = new UserPrincipal(user);

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER");
    }

    @Test
    void nullRoles_producesEmptyAuthorities() {
        assertThat(new UserPrincipal(userWithoutRoles("jdoe")).getAuthorities()).isEmpty();
    }

    @Test
    void tokenConstructor_buildsLightweightPrincipalWithoutUser() {
        UserPrincipal principal = new UserPrincipal(USER_ID, "jdoe", Set.of("ROLE_USER", "ROLE_ADMIN"));

        assertThat(principal.getUsername()).isEqualTo("jdoe");
        assertThat(principal.getUserId()).isEqualTo(USER_ID);
        assertThat(principal.getUser()).isNull();
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void disabledUser_reflectsInPrincipal() {
        assertThat(new UserPrincipal(disabledUser("jdoe")).isEnabled()).isFalse();
    }
}