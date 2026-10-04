package com.msj.securefile.storage.domain.scan;

/**
 * Small and large files are scanned by separate workers, so a 2 GB scan never delays the small ones.
 */
public enum ScanQueue {
    SMALL,
    LARGE
}