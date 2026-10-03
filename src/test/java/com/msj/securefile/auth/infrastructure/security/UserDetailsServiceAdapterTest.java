package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.application.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static com.msj.securefile.auth.support.UserTestFactory.activeUser;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceAdapterTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDetailsServiceAdapter adapter;

    @Test
    void loadUserByUsername_found_returnsUserPrincipal() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(activeUser("jdoe")));

        UserDetails result = adapter.loadUserByUsername("jdoe");

        assertThat(result).isInstanceOf(UserPrincipal.class);
        assertThat(result.getUsername()).isEqualTo("jdoe");
    }

    @Test
    void loadUserByUsername_notFound_throwsUsernameNotFoundException() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.loadUserByUsername("unknown"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}