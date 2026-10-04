package com.msj.securefile.storage.domain.scan;

import java.time.Duration;

/**
 * How a failing scan is retried: exponential backoff between attempts, and a limit after which the file is given up
 * on. Only failed scans count; an outage of ClamAV does not (see ScanJob).
 */
public record ScanRetryPolicy(int maxAttempts, Duration baseBackoff, Duration maxBackoff) {

    public ScanRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("At least one attempt is required");
        if (baseBackoff == null || baseBackoff.isZero() || baseBackoff.isNegative()) {
            throw new IllegalArgumentException("The base backoff must be positive");
        }
        if (maxBackoff == null || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("The maximum backoff cannot be shorter than the base backoff");
        }
    }

    /**
     * Delay before the retry that follows the given failed attempt (1 for the first failure).
     */
    public Duration backoff(int failedAttempts) {
        if (failedAttempts < 1) throw new IllegalArgumentException("The attempt number starts at 1");

        // Doubling stops once the cap is reached, so a huge attempt count can neither overflow nor loop long.
        Duration delay = baseBackoff;
        for (int attempt = 1; attempt < failedAttempts && delay.compareTo(maxBackoff) < 0; attempt++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    public boolean isExhausted(int failedAttempts) {
        return failedAttempts >= maxAttempts;
    }
}