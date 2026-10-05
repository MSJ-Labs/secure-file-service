package com.msj.securefile.storage.domain.file.exception;

/**
 * Also raised for a file that belongs to someone else: the fixed message must not tell the two cases apart.
 */
public class SecureFileNotFoundException extends RuntimeException {

    public SecureFileNotFoundException() {
        super("File not found.");
    }
}