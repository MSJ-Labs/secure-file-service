package com.msj.securefile.storage.domain.scan.exception;

public class LeaseNotExpiredException extends RuntimeException {

    public LeaseNotExpiredException() {
        super("The scan lease has not expired yet.");
    }
}