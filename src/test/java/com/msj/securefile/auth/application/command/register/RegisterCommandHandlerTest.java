package com.msj.securefile.auth.application.command.register;

import com.msj.securefile.auth.application.port.out.PasswordHasher;
import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.application.result.UserProfile;
import com.msj.securefile.auth.domain.user.EmailAlreadyExistsException;
import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UsernameAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.msj.securefile.auth.support.UserTestFactory.CLOCK;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterCommandHandlerTest {

    @Mock UserRepository userRepository;
    @Mock PasswordHasher passwordHasher;

    private RegisterCommandHandler handler;
    private RegisterCommand validCommand;

    @BeforeEach
    void setUp() {
        handler = new RegisterCommandHandler(userRepository, passwordHasher, CLOCK);
        validCommand = new RegisterCommand("jdoe", "jdoe@example.com", "secret123", "John", "Doe");
    }

    @Test
    void register_success() {
        when(userRepository.existsByUsername("jdoe")).thenReturn(false);
        when(userRepository.existsByEmail("jdoe@example.com")).thenReturn(false);
        when(passwordHasher.encode("secret123")).thenReturn("$hashed$");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfile result = handler.handle(validCommand);

        assertThat(result.username()).isEqualTo("jdoe");
        assertThat(result.email()).isEqualTo("jdoe@example.com");
        assertThat(result.roles()).contains("ROLE_USER");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_throwsWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("jdoe")).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(validCommand))
                .isInstanceOf(UsernameAlreadyExistsException.class)
                .hasMessageContaining("jdoe");

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_throwsWhenEmailAlreadyExists() {
        when(userRepository.existsByUsername("jdoe")).thenReturn(false);
        when(userRepository.existsByEmail("jdoe@example.com")).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(validCommand))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("jdoe@example.com");

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_savesTheHashNeverTheClearPassword() {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordHasher.encode("secret123")).thenReturn("bcrypt_hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        handler.handle(validCommand);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("bcrypt_hash");
        verify(passwordHasher).encode("secret123");
    }
}