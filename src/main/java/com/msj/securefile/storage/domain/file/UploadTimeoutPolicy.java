package com.msj.securefile.storage.domain.file;

import java.time.Duration;
import java.time.Instant;

/**
 * How long an upload may stay UPLOADING. Nothing renews it while the body streams, so the deadline grows with the
 * declared size: a fixed delay would either kill a legitimate 2 GB upload or keep a stuck small one for too long.
 */
public record UploadTimeoutPolicy(Duration baseDelay, long minBytesPerSecond) {

    public UploadTimeoutPolicy {
        if (baseDelay == null || baseDelay.isZero() || baseDelay.isNegative()) {
            throw new IllegalArgumentException("The base delay must be positive");
        }
        if (minBytesPerSecond <= 0) throw new IllegalArgumentException("The minimum rate must be positive");
    }

    public Instant deadline(Instant now, long declaredSize) {
        if (declaredSize < 0) throw new IllegalArgumentException("File size cannot be negative");
        // Rounded up: an upload that is just fast enough must not be cut by a partial second.
        long transferSeconds = Math.ceilDiv(declaredSize, minBytesPerSecond);
        return now.plus(baseDelay).plusSeconds(transferSeconds);
    }
}