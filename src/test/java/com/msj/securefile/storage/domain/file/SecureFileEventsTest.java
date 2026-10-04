package com.msj.securefile.storage.domain.file;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.storage.domain.file.event.*;
import com.msj.securefile.storage.domain.file.exception.InvalidFileTransitionException;
import com.msj.securefile.storage.domain.file.exception.UploadSizeMismatchException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every successful transition records exactly one event carrying its own data: this is the audit trail, so a
 * refused transition must leave no trace.
 */
class SecureFileEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(3_600);
    private static final Instant UPLOADED_AT = NOW.plusSeconds(30);
    private static final Instant SCAN_START = NOW.plusSeconds(60);
    private static final Instant VERDICT_AT = NOW.plusSeconds(120);
    private static final FileId ID = FileId.of(42L);
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));
    private static final String SIGNATURE = "Win.Test.EICAR_HDB-1";

    // The creation event is dropped so each test only sees the event of the transition it exercises.
    private static SecureFile uploading() {
        SecureFile file = SecureFile.initiate(ID, OWNER, "report.pdf", 1_024, NOW, EXPIRES);
        file.pullDomainEvents();
        return file;
    }

    private static SecureFile pending() {
        SecureFile file = uploading();
        file.completeUpload(DIGEST, 1_024, UPLOADED_AT);
        file.pullDomainEvents();
        return file;
    }

    private static SecureFile scanning() {
        SecureFile file = pending();
        file.startScan(SCAN_START);
        file.pullDomainEvents();
        return file;
    }

    @Test
    void initiate_recordsFileUploadStarted() {
        SecureFile file = SecureFile.initiate(ID, OWNER, "  report.pdf ", 1_024, NOW, EXPIRES);

        // The event carries the normalized name, as stored.
        assertThat(file.pullDomainEvents())
                .containsExactly(new FileUploadStarted(ID, OWNER, "report.pdf", 1_024, NOW));
    }

    @Test
    void completeUpload_recordsUploadCompletedWithTheDigestAndTheRealSize() {
        SecureFile file = uploading();

        file.completeUpload(DIGEST, 1_024, UPLOADED_AT);

        assertThat(file.pullDomainEvents()).containsExactly(new UploadCompleted(ID, DIGEST, 1_024, UPLOADED_AT));
    }

    @Test
    void failUpload_recordsUploadFailedWithItsReason() {
        SecureFile file = uploading();

        file.failUpload(UploadFailureReason.TIMEOUT, VERDICT_AT);

        assertThat(file.pullDomainEvents())
                .containsExactly(new UploadFailed(ID, UploadFailureReason.TIMEOUT, VERDICT_AT));
    }

    @Test
    void startScan_recordsScanStarted() {
        SecureFile file = pending();

        file.startScan(SCAN_START);

        assertThat(file.pullDomainEvents()).containsExactly(new ScanStarted(ID, SCAN_START));
    }

    @Test
    void requeueScan_recordsScanRequeued() {
        SecureFile file = scanning();

        file.requeueScan(VERDICT_AT);

        assertThat(file.pullDomainEvents()).containsExactly(new ScanRequeued(ID, VERDICT_AT));
    }

    @Test
    void markClean_recordsFileFoundClean() {
        SecureFile file = scanning();

        file.markClean(VERDICT_AT);

        assertThat(file.pullDomainEvents()).containsExactly(new FileFoundClean(ID, VERDICT_AT));
    }

    @Test
    void markInfected_recordsFileFoundInfectedWithTheSignature() {
        SecureFile file = scanning();

        file.markInfected(SIGNATURE, VERDICT_AT);

        assertThat(file.pullDomainEvents()).containsExactly(new FileFoundInfected(ID, SIGNATURE, VERDICT_AT));
    }

    @Test
    void failScan_recordsScanFailed() {
        SecureFile file = scanning();

        file.failScan(VERDICT_AT);

        assertThat(file.pullDomainEvents()).containsExactly(new ScanFailed(ID, VERDICT_AT));
    }

    @Test
    void aRefusedTransitionRecordsNothing() {
        SecureFile uploading = uploading();
        SecureFile scanning = scanning();

        assertThatThrownBy(() -> uploading.completeUpload(DIGEST, 1, UPLOADED_AT))
                .isInstanceOf(UploadSizeMismatchException.class);
        assertThatThrownBy(() -> uploading.startScan(SCAN_START)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> scanning.markInfected(" ", VERDICT_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scanning.failUpload(UploadFailureReason.ABORTED, VERDICT_AT))
                .isInstanceOf(InvalidFileTransitionException.class);

        assertThat(uploading.pullDomainEvents()).isEmpty();
        assertThat(scanning.pullDomainEvents()).isEmpty();
    }

    @Test
    void theWholeLifecycleIsRecordedInOrder() {
        SecureFile file = SecureFile.initiate(ID, OWNER, "report.pdf", 1_024, NOW, EXPIRES);
        file.completeUpload(DIGEST, 1_024, UPLOADED_AT);
        file.startScan(SCAN_START);
        file.requeueScan(SCAN_START.plusSeconds(1));
        file.startScan(SCAN_START.plusSeconds(2));
        file.markClean(VERDICT_AT);

        List<DomainEvent> events = file.pullDomainEvents();

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("FileUploadStarted", "UploadCompleted", "ScanStarted", "ScanRequeued",
                        "ScanStarted", "FileFoundClean");
        assertThat(events).extracting(DomainEvent::occurredOn)
                .containsExactly(NOW, UPLOADED_AT, SCAN_START, SCAN_START.plusSeconds(1),
                        SCAN_START.plusSeconds(2), VERDICT_AT);
    }

    // Every event says which file it is about, so the audit writer can store it without knowing the type.
    @Test
    void everyEventIdentifiesItsFile() {
        SecureFile file = SecureFile.initiate(ID, OWNER, "report.pdf", 1_024, NOW, EXPIRES);
        file.completeUpload(DIGEST, 1_024, UPLOADED_AT);
        file.startScan(SCAN_START);
        file.markInfected(SIGNATURE, VERDICT_AT);

        assertThat(file.pullDomainEvents())
                .allSatisfy(event -> assertThat(((FileEvent) event).fileId()).isEqualTo(ID));
    }
}