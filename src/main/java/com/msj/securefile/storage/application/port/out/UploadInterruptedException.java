package com.msj.securefile.storage.application.port.out;

/**
 * Reading the body failed: the client stopped sending or disconnected. Kept apart from FileStorageException, which is
 * about writing, because the cause of the failure is not the same and is recorded as such.
 */
public class UploadInterruptedException extends RuntimeException {

    public UploadInterruptedException(Throwable cause) {
        super("Upload interrupted.", cause);
    }
}