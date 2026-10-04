package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.exception.FileNotDownloadableException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureFileDownloadTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));

    private static SecureFile uploading() {
        return SecureFile.initiate(FileId.of(42L), OwnerId.of(7L), "report.pdf", 1_000, NOW, NOW.plusSeconds(3_600));
    }

    private static SecureFile pending() {
        SecureFile file = uploading();
        file.completeUpload(DIGEST, 1_000, NOW);
        return file;
    }

    private static SecureFile scanning() {
        SecureFile file = pending();
        file.startScan(NOW);
        return file;
    }

    private static SecureFile clean() {
        SecureFile file = scanning();
        file.markClean(NOW);
        return file;
    }

    // Every status except CLEAN: only a file the scan cleared may leave the quarantine.
    private static Stream<SecureFile> filesThatMustNotBeDownloaded() {
        SecureFile uploadFailed = uploading();
        uploadFailed.failUpload(UploadFailureReason.ABORTED, NOW);
        SecureFile infected = scanning();
        infected.markInfected("Win.Test.EICAR_HDB-1", NOW);
        SecureFile scanFailed = scanning();
        scanFailed.failScan(NOW);
        return Stream.of(uploading(), uploadFailed, pending(), scanning(), infected, scanFailed);
    }

    @Test
    void ensureDownloadable_acceptsACleanFile() {
        assertThatCode(() -> clean().ensureDownloadable()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("filesThatMustNotBeDownloaded")
    void ensureDownloadable_refusesAnyFileThatIsNotClean(SecureFile file) {
        assertThatThrownBy(file::ensureDownloadable).isInstanceOf(FileNotDownloadableException.class);
    }
}