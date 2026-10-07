package com.msj.securefile.storage.infrastructure.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingPolicyTest {

    private static final Duration MIN = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(30);

    private final PollingPolicy policy = new PollingPolicy(MIN, MAX);

    @Test
    void afterHit_pollsAgainImmediately() {
        // Work was found: there may be more, so the queue is drained without waiting.
        assertThat(policy.afterHit()).isZero();
    }

    @Test
    void afterMiss_startsFromTheMinimumDelayWhenTheLastPollFoundWork() {
        assertThat(policy.afterMiss(Duration.ZERO)).isEqualTo(MIN);
    }

    @Test
    void afterMiss_doublesTheDelayAtEachConsecutiveMiss() {
        Duration first = policy.afterMiss(Duration.ZERO);
        Duration second = policy.afterMiss(first);
        Duration third = policy.afterMiss(second);

        assertThat(second).isEqualTo(Duration.ofSeconds(2));
        assertThat(third).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void afterMiss_neverExceedsTheMaximumDelay() {
        Duration delay = Duration.ZERO;
        for (int miss = 0; miss < 50; miss++) {
            delay = policy.afterMiss(delay);
        }

        // At night, with nothing to do, the worker settles on the longest wait instead of polling uselessly.
        assertThat(delay).isEqualTo(MAX);
    }

    @Test
    void afterMiss_staysAtTheMaximumOnceReached() {
        assertThat(policy.afterMiss(MAX)).isEqualTo(MAX);
    }

    @Test
    void afterHit_resetsTheBackoffEvenFromTheMaximumDelay() {
        Duration delay = policy.afterHit();

        // The first miss after a hit starts again from the minimum, not from where the backoff had reached.
        assertThat(policy.afterMiss(delay)).isEqualTo(MIN);
    }

    @Test
    void constructor_refusesAnInconsistentConfiguration() {
        Duration zero = Duration.ZERO;
        Duration negative = Duration.ofSeconds(-1);
        Duration shorterThanMin = Duration.ofMillis(500);

        assertThatThrownBy(() -> new PollingPolicy(zero, MAX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingPolicy(negative, MAX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingPolicy(null, MAX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingPolicy(MIN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingPolicy(MIN, shorterThanMin)).isInstanceOf(IllegalArgumentException.class);
    }
}