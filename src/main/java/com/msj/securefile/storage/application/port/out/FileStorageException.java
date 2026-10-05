package com.msj.securefile.storage.application.port.out;

/**
 * The binary storage failed. The adapter translates its own errors into this one so the application never sees
 * SDK types. The message is fixed: the cause carries the technical detail.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(Throwable cause) {
        super("File storage failed.", cause);
    }
}