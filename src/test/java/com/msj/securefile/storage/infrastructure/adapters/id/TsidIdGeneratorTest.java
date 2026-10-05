package com.msj.securefile.storage.infrastructure.adapters.id;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TsidIdGeneratorTest {

    private static final int COUNT = 10_000;

    private final TsidIdGenerator generator = new TsidIdGenerator();

    @Test
    void nextFileId_neverReturnsTheSameIdTwice() {
        Set<FileId> ids = new HashSet<>();
        for (int i = 0; i < COUNT; i++) {
            ids.add(generator.nextFileId());
        }

        assertThat(ids).hasSize(COUNT);
    }

    @Test
    void nextScanJobId_neverReturnsTheSameIdTwice() {
        Set<ScanJobId> ids = new HashSet<>();
        for (int i = 0; i < COUNT; i++) {
            ids.add(generator.nextScanJobId());
        }

        assertThat(ids).hasSize(COUNT);
    }
}