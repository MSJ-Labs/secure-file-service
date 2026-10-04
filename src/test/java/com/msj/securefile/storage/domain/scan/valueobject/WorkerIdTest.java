package com.msj.securefile.storage.domain.scan.valueobject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerIdTest {

    @Test
    void of_keepsTheName() {
        assertThat(WorkerId.of("worker-1").value()).isEqualTo("worker-1");
    }

    @Test
    void of_trimsTheName() {
        assertThat(WorkerId.of("  worker-1 ").value()).isEqualTo("worker-1");
    }

    @Test
    void of_rejectsABlankName() {
        assertThatThrownBy(() -> WorkerId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerId.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    // The lease_owner column is VARCHAR(128).
    @Test
    void of_rejectsANameLongerThan128Characters() {
        assertThat(WorkerId.of("w".repeat(128)).value()).hasSize(128);
        assertThatThrownBy(() -> WorkerId.of("w".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }
}