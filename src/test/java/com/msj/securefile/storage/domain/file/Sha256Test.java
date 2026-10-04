package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sha256Test {

    private static final String VALID = "a".repeat(64);

    @Test
    void of_acceptsALowercaseHexDigest() {
        assertThat(Sha256.of(VALID).value()).isEqualTo(VALID);
    }

    @Test
    void of_rejectsAMissingDigest() {
        assertThatThrownBy(() -> Sha256.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsAWrongLength() {
        assertThatThrownBy(() -> Sha256.of("a".repeat(63))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sha256.of("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    // The database CHECK only accepts lowercase hex: one canonical form, no normalization surprises.
    @Test
    void of_rejectsUppercaseAndNonHexCharacters() {
        assertThatThrownBy(() -> Sha256.of("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sha256.of("g".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
}