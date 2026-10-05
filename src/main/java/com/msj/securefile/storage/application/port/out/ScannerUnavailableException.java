package com.msj.securefile.storage.application.port.out;

/**
 * The scanner could not be reached (down, refusing connections). Nothing is known about the file, so this must never
 * count against it.
 */
public class ScannerUnavailableException extends RuntimeException {

    public ScannerUnavailableException(Throwable cause) {
        super("Scanner unavailable.", cause);
    }
}