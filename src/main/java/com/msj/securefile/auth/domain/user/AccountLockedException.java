package com.msj.securefile.auth.domain.user;

public class AccountLockedException extends RuntimeException {

    public AccountLockedException() {
        super("Account is locked. Try again later.");
    }
}