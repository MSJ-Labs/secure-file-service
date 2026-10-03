package com.msj.securefile.auth.application.command.refresh;

import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.domain.token.InvalidRefreshTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static com.msj.securefile.auth.support.UserTestFactory.CLOCK;
import static com.msj.securefile.auth.support.UserTestFactory.activeUser;
import static com.msj.securefile.auth.support.UserTestFactory.disabledUser;
import static com.msj.securefile.auth.support.UserTestFactory.lockedUser;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCommandHandlerTest {

    @Mock private TokenService tokenService;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;

    private RefreshTokenCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RefreshTokenCommandHandler(tokenService, refreshTokenRepository, userRepository, CLOCK);
    }

    private void givenAValidRefreshTokenOf(String username) {
        when(tokenService.validateToken("valid-refresh")).thenReturn(true);
        when(refreshTokenRepository.isValid(anyString())).thenReturn(true);
        when(tokenService.getUsernameFromToken("valid-refresh")).thenReturn(username);
    }

    @Test
    void handle_validToken_returnsNewAccessTokenWithTheRolesOfTheAccount() {
        givenAValidRefreshTokenOf("jdoe");
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(activeUser("jdoe")));
        when(tokenService.generateAccessToken("jdoe", Set.of("ROLE_USER"))).thenReturn("new-access-token");

        String result = handler.handle(new RefreshTokenCommand("valid-refresh"));

        assertThat(result).isEqualTo("new-access-token");
    }

    @Test
    void handle_invalidJwt_throwsInvalidRefreshToken() {
        when(tokenService.validateToken("bad-token")).thenReturn(false);
        RefreshTokenCommand command = new RefreshTokenCommand("bad-token");

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("refresh token");
    }

    @Test
    void handle_revokedToken_throwsInvalidRefreshToken() {
        when(tokenService.validateToken("revoked-token")).thenReturn(true);
        when(refreshTokenRepository.isValid(anyString())).thenReturn(false);
        RefreshTokenCommand command = new RefreshTokenCommand("revoked-token");

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("refresh token");
    }

    @Test
    void handle_accountNoLongerExists_throwsInvalidRefreshToken() {
        givenAValidRefreshTokenOf("ghost");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        RefreshTokenCommand command = new RefreshTokenCommand("valid-refresh");

        assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(tokenService, never()).generateAccessToken(anyString(), any());
    }

    @Test
    void handle_disabledAccount_throwsInvalidRefreshToken() {
        givenAValidRefreshTokenOf("jdoe");
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(disabledUser("jdoe")));
        RefreshTokenCommand command = new RefreshTokenCommand("valid-refresh");

        assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(tokenService, never()).generateAccessToken(anyString(), any());
    }

    @Test
    void handle_lockedAccount_throwsInvalidRefreshToken() {
        givenAValidRefreshTokenOf("locked");
        when(userRepository.findByUsername("locked")).thenReturn(Optional.of(lockedUser("locked")));
        RefreshTokenCommand command = new RefreshTokenCommand("valid-refresh");

        assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(tokenService, never()).generateAccessToken(anyString(), any());
    }
}