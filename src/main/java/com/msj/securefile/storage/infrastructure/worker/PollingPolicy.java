package com.msj.securefile.storage.infrastructure.worker;

import java.time.Duration;

/**
 * How long a queue loop waits before its next poll: no wait while work keeps coming, a delay that doubles from
 * {@code min} up to {@code max} while the queue stays empty. The bounds are hypotheses to calibrate with a load test.
 */
public record PollingPolicy(Duration min, Duration max) {

    public PollingPolicy {
        if (min == null || min.isZero() || min.isNegative()) {
            throw new IllegalArgumentException("The minimum polling delay must be positive");
        }
        if (max == null || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("The maximum polling delay cannot be shorter than the minimum");
        }
    }

    public Duration afterHit() {
        return Duration.ZERO;
    }

    public Duration afterMiss(Duration previousDelay) {
        if (previousDelay.compareTo(min) < 0) return min;

        Duration doubled = previousDelay.multipliedBy(2);
        return doubled.compareTo(max) > 0 ? max : doubled;
    }
}
