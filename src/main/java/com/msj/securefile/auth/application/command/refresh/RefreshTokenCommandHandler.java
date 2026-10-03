package com.msj.securefile.auth.application.command.refresh;

import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.domain.token.InvalidRefreshTokenException;
import com.msj.securefile.auth.domain.token.TokenHasher;
import com.msj.securefile.auth.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCommandHandler {

    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Three-layer validation:
     *   1. Signature + expiry  — stateless, catches forged/expired tokens
     *   2. DB revocation check — stateful, catches tokens that are technically valid but
     *      were explicitly invalidated (logout, password change, stolen token revocation).
     *      We store and compare SHA-256 hashes, never the raw token.
     *   3. Current account state — a disabled or locked account, or one that no longer exists, gets no new
     *      access token. Roles come from the account, not from the (up to 7 days old) refresh token.
     */
    public String handle(RefreshTokenCommand command) {
        String token = command.refreshToken();

        if (!tokenService.validateToken(token)) {
            throw new InvalidRefreshTokenException();
        }

        if (!refreshTokenRepository.isValid(TokenHasher.hash(token))) {
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findByUsername(tokenService.getUsernameFromToken(token))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (!user.isEnabled() || user.isLockedAt(LocalDateTime.now(clock))) {
            throw new InvalidRefreshTokenException();
        }

        log.info("Refreshing access token for user: {}", user.getUsername());
        return tokenService.generateAccessToken(user.getUsername(), user.getRoles());
    }
}