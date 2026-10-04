package com.msj.securefile.storage.domain.file;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadTimeoutPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final long ONE_MIB = 1024 * 1024;
    private static final long TWO_GIB = 2L * 1024 * ONE_MIB;

    private final UploadTimeoutPolicy policy = new UploadTimeoutPolicy(Duration.ofSeconds(60), ONE_MIB);

    @Test
    void deadline_isTheBaseDelayPlusTheTimeNeededAtTheMinimumRate() {
        // 2 GiB at 1 MiB/s = 2048 s on top of the 60 s base.
        assertThat(policy.deadline(NOW, TWO_GIB)).isEqualTo(NOW.plusSeconds(60 + 2_048));
    }

    @Test
    void deadline_ofAnEmptyFileIsTheBaseDelay() {
        assertThat(policy.deadline(NOW, 0)).isEqualTo(NOW.plusSeconds(60));
    }

    // A partial second still costs a second: never cut an upload that is just fast enough.
    @Test
    void deadline_roundsTheTransferTimeUp() {
        assertThat(policy.deadline(NOW, ONE_MIB + 1)).isEqualTo(NOW.plusSeconds(60 + 2));
    }

    @Test
    void deadline_rejectsANegativeSize() {
        assertThatThrownBy(() -> policy.deadline(NOW, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsANonPositiveBaseDelay() {
        assertThatThrownBy(() -> new UploadTimeoutPolicy(Duration.ZERO, ONE_MIB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadTimeoutPolicy(null, ONE_MIB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsANonPositiveMinimumRate() {
        assertThatThrownBy(() -> new UploadTimeoutPolicy(Duration.ofSeconds(60), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}