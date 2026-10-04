package com.msj.securefile.storage.domain.scan;

/**
 * Chooses the queue of a scan from the file size, so a large scan never delays the small ones. The threshold is a
 * hypothesis to calibrate against measured scan durations.
 */
public record ScanQueuePolicy(long smallMaxBytes) {

    public ScanQueuePolicy {
        if (smallMaxBytes <= 0) throw new IllegalArgumentException("The small queue threshold must be positive");
    }

    public ScanQueue queueFor(long sizeBytes) {
        if (sizeBytes < 0) throw new IllegalArgumentException("File size cannot be negative");

        return sizeBytes <= smallMaxBytes ? ScanQueue.SMALL : ScanQueue.LARGE;
    }
}