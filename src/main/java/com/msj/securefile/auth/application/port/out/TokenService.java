package com.msj.securefile.auth.application.port.out;

import com.msj.securefile.auth.domain.user.UserId;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Output port — issues and verifies the signed tokens. The token format (JWT) is an infrastructure choice.
 */
public interface TokenService {

    String generateAccessToken(UserId userId, String username, Set<String> roles);

    String generateRefreshToken(UserId userId, String username, Set<String> roles);

    /** True when the signature is valid and the token has not expired. */
    boolean validateToken(String token);

    String getUsernameFromToken(String token);

    /** Empty for a token issued without the user id: valid, but it cannot say who the caller is. */
    Optional<UserId> getUserIdFromToken(String token);

    Set<String> getRolesFromToken(String token);

    LocalDateTime getExpirationFromToken(String token);
}