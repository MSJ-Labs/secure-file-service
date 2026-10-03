package com.msj.securefile.auth.application.port.out;

/**
 * Output port — one-way password hashing. The algorithm is an infrastructure choice.
 */
public interface PasswordHasher {

    String encode(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}