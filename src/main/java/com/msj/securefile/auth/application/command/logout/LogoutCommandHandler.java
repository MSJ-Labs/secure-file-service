package com.msj.securefile.auth.application.command.logout;

import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import com.msj.securefile.auth.domain.token.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutCommandHandler {

    private final RefreshTokenRepository refreshTokenRepository;

    public void handle(LogoutCommand command) {
        log.info("Logging out user: {}", command.username());
        String refreshToken = command.rawRefreshToken();
        if (refreshToken != null && !refreshToken.isEmpty()) {
            refreshTokenRepository.revoke(TokenHasher.hash(refreshToken));
        }
    }
}