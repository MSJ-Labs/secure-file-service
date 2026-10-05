package com.msj.securefile.auth.application.command.login;

import com.msj.securefile.auth.application.port.out.PasswordHasher;
import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import com.msj.securefile.auth.application.port.out.TokenService;
import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.application.result.UserProfile;
import com.msj.securefile.auth.domain.token.TokenHasher;
import com.msj.securefile.auth.domain.user.AccountLockedException;
import com.msj.securefile.auth.domain.user.InvalidCredentialsException;
import com.msj.securefile.auth.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginCommandHandler {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    // A wrong password throws InvalidCredentialsException after saving the failed attempt: it must not roll
    // that save back, or the attempts never accumulate and the account never locks.
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public LoginResult handle(LoginCommand command) {
        log.info("Login attempt for user: {}", command.username());

        // Unknown user, disabled account and wrong password look the same to the caller.
        User user = userRepository.findByUsername(command.username())
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.isEnabled()) {
            throw new InvalidCredentialsException();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.isLockedAt(now)) {
            throw new AccountLockedException();
        }

        if (!passwordHasher.matches(command.password(), user.getPasswordHash())) {
            user.recordFailedLoginAttempt(now);
            userRepository.save(user);
            log.warn("Failed login attempt for user: {} ({} attempts)",
                    command.username(), user.getFailedLoginAttempts());
            throw new InvalidCredentialsException();
        }

        user.recordSuccessfulLogin(now);
        userRepository.save(user);

        String accessToken = tokenService.generateAccessToken(user.getId(), user.getUsername(), user.getRoles());
        String refreshToken = tokenService.generateRefreshToken(user.getId(), user.getUsername(), user.getRoles());

        refreshTokenRepository.save(
                TokenHasher.hash(refreshToken),
                user.getId(),
                tokenService.getExpirationFromToken(refreshToken)
        );

        log.info("User logged in successfully: {}", command.username());
        return new LoginResult(accessToken, refreshToken, UserProfile.from(user));
    }
}