package com.msj.securefile.auth.domain.user;

/**
 * Wrong password, unknown user or disabled account. The message is deliberately the same in every case:
 * the caller must not learn which usernames exist or what state an account is in.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}