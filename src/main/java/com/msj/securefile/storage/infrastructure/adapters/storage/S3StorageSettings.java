package com.msj.securefile.storage.infrastructure.adapters.storage;

/**
 * Where the zones live and how a body is cut into parts. The memory of one upload in flight is about one part, so the
 * part size times the number of simultaneous uploads is what the instance must be able to hold.
 */
public record S3StorageSettings(String quarantineBucket, String cleanBucket, int partSizeBytes) {

    public S3StorageSettings {
        if (quarantineBucket == null || quarantineBucket.isBlank()) {
            throw new IllegalArgumentException("The quarantine bucket is required");
        }
        if (cleanBucket == null || cleanBucket.isBlank()) {
            throw new IllegalArgumentException("The clean bucket is required");
        }
        if (partSizeBytes <= 0) throw new IllegalArgumentException("The part size must be positive");
    }
}
