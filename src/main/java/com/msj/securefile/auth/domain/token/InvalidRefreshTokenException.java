package com.msj.securefile.auth.domain.token;

/**
 * Forged, expired or revoked refresh token.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}