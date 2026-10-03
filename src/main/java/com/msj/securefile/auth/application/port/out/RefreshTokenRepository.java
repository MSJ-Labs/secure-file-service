package com.msj.securefile.auth.application.port.out;

import com.msj.securefile.auth.domain.user.UserId;

import java.time.LocalDateTime;

/**
 * Output port — refresh tokens are stored as hashes, so a leak of the table does not leak usable tokens.
 */
public interface RefreshTokenRepository {

    void save(String tokenHash, UserId userId, LocalDateTime expiresAt);

    boolean isValid(String tokenHash);

    void revoke(String tokenHash);

    void revokeAllByUserId(UserId userId);
}