package com.msj.securefile.storage.domain.scan.exception;

public class ScanJobNotDueException extends RuntimeException {

    public ScanJobNotDueException() {
        super("The scan job is not due yet.");
    }
}