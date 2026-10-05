package com.msj.securefile.storage.application.port.out;

/**
 * The scanner was reached but the scan did not complete (timeout, protocol error). It counts as an attempt.
 */
public class ScanExecutionException extends RuntimeException {

    public ScanExecutionException(Throwable cause) {
        super("Scan failed.", cause);
    }
}