package com.msj.securefile.auth.application.command.login;

import com.msj.securefile.auth.application.port.out.PasswordHasher;
import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.domain.user.AccountLockedException;
import com.msj.securefile.auth.domain.user.InvalidCredentialsException;
import com.msj.securefile.auth.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.msj.securefile.auth.support.UserTestFactory.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginCommandHandlerTest {

    @Mock UserRepository userRepository;
    @Mock PasswordHasher passwordHasher;
    @Mock TokenService tokenService;
    @Mock RefreshTokenRepository refreshTokenRepository;

    private LoginCommandHandler handler;
    private User activeUser;

    @BeforeEach
    void setUp() {
        handler = new LoginCommandHandler(userRepository, passwordHasher, tokenService, refreshTokenRepository, CLOCK);
        activeUser = activeUser("jdoe");
    }

    @Test
    void login_success() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("pass", "$hashed$")).thenReturn(true);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenService.generateAccessToken(eq("jdoe"), any())).thenReturn("access-token");
        when(tokenService.generateRefreshToken(eq("jdoe"), any())).thenReturn("refresh-token");
        when(tokenService.getExpirationFromToken("refresh-token")).thenReturn(LocalDateTime.now().plusDays(7));

        LoginResult result = handler.handle(new LoginCommand("jdoe", "pass"));

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.user().username()).isEqualTo("jdoe");
    }

    @Test
    void login_throwsTheSameErrorWhenUserNotFound() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        LoginCommand command = new LoginCommand("unknown", "pass");
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void login_throwsOnBadPassword() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("wrong", "$hashed$")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginCommand command = new LoginCommand("jdoe", "wrong");
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(activeUser.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void login_locksAccountAfterFiveFailures() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginCommand command = new LoginCommand("jdoe", "wrong");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> handler.handle(command))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(activeUser.isLockedAt(NOW)).isTrue();
        assertThat(activeUser.getLockedUntil()).isNotNull();
    }

    @Test
    void login_throwsWhenAccountLocked() {
        when(userRepository.findByUsername("locked")).thenReturn(Optional.of(lockedUser()));

        LoginCommand command = new LoginCommand("locked", "pass");
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(AccountLockedException.class);
    }
}