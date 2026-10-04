package com.msj.securefile.storage.domain.scan;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanRetryPolicyTest {

    private final ScanRetryPolicy policy = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));

    @Test
    void backoff_doublesWithEachFailedAttempt() {
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void backoff_isCappedByTheMaximum() {
        assertThat(policy.backoff(10)).isEqualTo(Duration.ofMinutes(5));
    }

    // 2^(attempts - 1) overflows for a large attempt count: the cap must still hold.
    @Test
    void backoff_staysAtTheMaximumForAHugeNumberOfAttempts() {
        assertThat(policy.backoff(1_000)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.backoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void backoff_rejectsAnAttemptNumberBelowOne() {
        assertThatThrownBy(() -> policy.backoff(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isExhausted_whenTheFailedAttemptsReachTheMaximum() {
        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
        assertThat(policy.isExhausted(4)).isTrue();
    }

    @Test
    void constructor_rejectsInconsistentSettings() {
        assertThatThrownBy(() -> new ScanRetryPolicy(0, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanRetryPolicy(3, Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanRetryPolicy(3, null, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanRetryPolicy(3, Duration.ofMinutes(5), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}