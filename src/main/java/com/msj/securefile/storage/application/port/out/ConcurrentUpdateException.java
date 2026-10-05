package com.msj.securefile.storage.application.port.out;

/**
 * The aggregate changed in the database after it was loaded: writing it now would silently overwrite the other writer.
 * The caller reloads and decides again, or reports a conflict.
 */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException() {
        super("The resource was modified concurrently.");
    }
}
