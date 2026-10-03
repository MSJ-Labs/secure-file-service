package com.msj.securefile.auth.domain.user;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String message) {
        super(message);
    }

    public static UserNotFoundException forUsername(String username) {
        return new UserNotFoundException("User not found: " + username);
    }

    public static UserNotFoundException forId(UserId id) {
        return new UserNotFoundException("User not found with id: " + id.value());
    }
}