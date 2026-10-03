package com.msj.securefile.auth.application.command.logout;

import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogoutCommandHandlerTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private LogoutCommandHandler handler;

    @Test
    void handle_revokesRefreshToken() {
        handler.handle(new LogoutCommand("jdoe", "raw-refresh-token"));

        verify(refreshTokenRepository).revoke(anyString());
    }

    @Test
    void handle_skipsRevocationWhenRefreshTokenIsNull() {
        handler.handle(new LogoutCommand("jdoe", null));

        verify(refreshTokenRepository, never()).revoke(anyString());
    }

    @Test
    void handle_skipsRevocationWhenRefreshTokenIsEmpty() {
        handler.handle(new LogoutCommand("jdoe", ""));

        verify(refreshTokenRepository, never()).revoke(anyString());
    }
}