package com.msj.securefile.storage.application.port.out;

/**
 * The body is longer than the limit the storage was given. It stops reading at the limit, so a client cannot fill the
 * quarantine by sending more than it declared.
 */
public class UploadTooLargeException extends RuntimeException {

    public UploadTooLargeException() {
        super("The upload is larger than declared.");
    }
}
