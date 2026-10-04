package com.msj.securefile.storage.domain.scan.exception;

/**
 * The worker no longer owns the job: it must stop scanning and drop its result.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException() {
        super("The scan lease is no longer held by this worker.");
    }
}