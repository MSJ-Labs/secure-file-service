package com.msj.securefile.storage.domain.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanQueuePolicyTest {

    private static final long FIFTY_MIB = 50L * 1024 * 1024;

    private final ScanQueuePolicy policy = new ScanQueuePolicy(FIFTY_MIB);

    @Test
    void queueFor_aFileBelowTheThresholdIsSmall() {
        assertThat(policy.queueFor(1_024)).isEqualTo(ScanQueue.SMALL);
        assertThat(policy.queueFor(0)).isEqualTo(ScanQueue.SMALL);
    }

    // The threshold belongs to the small queue: only a strictly larger file goes to the large one.
    @Test
    void queueFor_aFileExactlyAtTheThresholdIsSmall() {
        assertThat(policy.queueFor(FIFTY_MIB)).isEqualTo(ScanQueue.SMALL);
    }

    @Test
    void queueFor_aFileAboveTheThresholdIsLarge() {
        assertThat(policy.queueFor(FIFTY_MIB + 1)).isEqualTo(ScanQueue.LARGE);
        assertThat(policy.queueFor(2L * 1024 * 1024 * 1024)).isEqualTo(ScanQueue.LARGE);
    }

    @Test
    void queueFor_rejectsANegativeSize() {
        assertThatThrownBy(() -> policy.queueFor(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsANonPositiveThreshold() {
        assertThatThrownBy(() -> new ScanQueuePolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanQueuePolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}