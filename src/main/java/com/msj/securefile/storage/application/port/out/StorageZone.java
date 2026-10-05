package com.msj.securefile.storage.application.port.out;

/**
 * Where the binary lives. A file enters in QUARANTINE and is only readable by users once it has been moved to CLEAN.
 */
public enum StorageZone {
    QUARANTINE,
    CLEAN
}