package com.msj.securefile.storage.domain.file.valueobject;

import io.hypersistence.tsid.TSID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileIdTest {

    @Test
    void asString_isTheTextFormOfTheTsid() {
        assertThat(FileId.of(42L).asString()).isEqualTo(TSID.from(42L).toString());
    }

    @Test
    void asString_roundTripsThroughTheTsid() {
        FileId id = FileId.of(42L);

        assertThat(new FileId(TSID.from(id.asString()))).isEqualTo(id);
    }

    @Test
    void constructor_refusesANullTsid() {
        assertThatThrownBy(() -> new FileId(null)).isInstanceOf(IllegalArgumentException.class);
    }
}