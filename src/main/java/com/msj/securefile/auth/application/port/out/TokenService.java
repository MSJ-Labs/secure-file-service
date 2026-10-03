package com.msj.securefile.auth.application.port.out;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Output port — issues and verifies the signed tokens. The token format (JWT) is an infrastructure choice.
 */
public interface TokenService {

    String generateAccessToken(String username, Set<String> roles);

    String generateRefreshToken(String username, Set<String> roles);

    /** True when the signature is valid and the token has not expired. */
    boolean validateToken(String token);

    String getUsernameFromToken(String token);

    Set<String> getRolesFromToken(String token);

    LocalDateTime getExpirationFromToken(String token);
}