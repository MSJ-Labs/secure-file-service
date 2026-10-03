package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.domain.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

import static com.msj.securefile.auth.support.UserTestFactory.activeUser;
import static com.msj.securefile.auth.support.UserTestFactory.disabledUser;
import static com.msj.securefile.auth.support.UserTestFactory.userWithoutRoles;
import static org.assertj.core.api.Assertions.assertThat;

class UserPrincipalTest {

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
        UserPrincipal principal = new UserPrincipal("jdoe", Set.of("ROLE_USER", "ROLE_ADMIN"));

        assertThat(principal.getUsername()).isEqualTo("jdoe");
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