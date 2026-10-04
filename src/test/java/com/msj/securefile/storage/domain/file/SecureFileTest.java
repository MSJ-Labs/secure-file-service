package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.exception.InvalidFileTransitionException;
import com.msj.securefile.storage.domain.file.exception.UploadSizeMismatchException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureFileTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(30);
    private static final Instant EXPIRES = NOW.plusSeconds(3_600);
    private static final Instant SCAN_START = NOW.plusSeconds(60);
    private static final Instant VERDICT_AT = NOW.plusSeconds(120);
    private static final String SIGNATURE = "Win.Test.EICAR_HDB-1";
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));
    private static final FileId ID = FileId.of(42L);
    private static final OwnerId OWNER = OwnerId.of(7L);

    private static SecureFile initiate(String name, long declaredSize) {
        return SecureFile.initiate(ID, OWNER, name, declaredSize, NOW, EXPIRES);
    }

    @Test
    void initiate_createsAnUploadingFileOwnedByTheCaller() {
        SecureFile file = initiate("report.pdf", 1_024);

        assertThat(file.id()).isEqualTo(ID);
        assertThat(file.getOwner()).isEqualTo(OWNER);
        assertThat(file.getName()).isEqualTo("report.pdf");
        assertThat(file.getDeclaredSize()).isEqualTo(1_024);
        assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOADING);
        assertThat(file.getCreatedAt()).isEqualTo(NOW);
        assertThat(file.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void initiate_trimsTheName() {
        assertThat(initiate("  report.pdf  ", 1).getName()).isEqualTo("report.pdf");
    }

    @Test
    void initiate_acceptsAnEmptyFile() {
        assertThat(initiate("empty.txt", 0).getDeclaredSize()).isZero();
    }

    @Test
    void initiate_rejectsABlankName() {
        assertThatThrownBy(() -> initiate("   ", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // The column is VARCHAR(255): fail in the domain rather than in the database.
    @Test
    void initiate_rejectsANameLongerThan255Characters() {
        assertThatThrownBy(() -> initiate("a".repeat(256), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initiate_rejectsANegativeSize() {
        assertThatThrownBy(() -> initiate("report.pdf", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initiate_rejectsAMissingOwner() {
        assertThatThrownBy(() -> SecureFile.initiate(ID, null, "report.pdf", 1, NOW, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initiate_hasNoDigestYet() {
        assertThat(initiate("report.pdf", 1).getSha256()).isEmpty();
    }

    @Test
    void completeUpload_movesToPendingAndRecordsTheDigest() {
        SecureFile file = initiate("report.pdf", 1_024);

        file.completeUpload(DIGEST, 1_024, LATER);

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getSha256()).contains(DIGEST);
        assertThat(file.getUpdatedAt()).isEqualTo(LATER);
        assertThat(file.getCreatedAt()).isEqualTo(NOW);
    }

    // A truncated or oversized body must never reach the scan queue.
    @Test
    void completeUpload_rejectsASizeDifferentFromTheDeclaredOne() {
        SecureFile file = initiate("report.pdf", 1_024);

        assertThatThrownBy(() -> file.completeUpload(DIGEST, 512, LATER))
                .isInstanceOf(UploadSizeMismatchException.class);
        assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOADING);
        assertThat(file.getSha256()).isEmpty();
    }

    @Test
    void completeUpload_rejectsAMissingDigest() {
        SecureFile file = initiate("report.pdf", 1);

        assertThatThrownBy(() -> file.completeUpload(null, 1, LATER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeUpload_isOnlyPossibleWhileUploading() {
        SecureFile file = initiate("report.pdf", 1);
        file.completeUpload(DIGEST, 1, LATER);

        assertThatThrownBy(() -> file.completeUpload(DIGEST, 1, LATER))
                .isInstanceOf(InvalidFileTransitionException.class);
    }

    @Test
    void initiate_keepsTheUploadDeadlineAndHasNoFailureReason() {
        SecureFile file = initiate("report.pdf", 1);

        assertThat(file.getUploadExpiresAt()).isEqualTo(EXPIRES);
        assertThat(file.getFailureReason()).isEmpty();
    }

    @Test
    void initiate_rejectsADeadlineThatIsNotInTheFuture() {
        assertThatThrownBy(() -> SecureFile.initiate(ID, OWNER, "report.pdf", 1, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecureFile.initiate(ID, OWNER, "report.pdf", 1, NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failUpload_marksTheUploadFailedWithItsReason() {
        SecureFile file = initiate("report.pdf", 1);

        file.failUpload(UploadFailureReason.TIMEOUT, LATER);

        assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOAD_FAILED);
        assertThat(file.getFailureReason()).contains(UploadFailureReason.TIMEOUT);
        assertThat(file.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void failUpload_rejectsAMissingReason() {
        SecureFile file = initiate("report.pdf", 1);

        assertThatThrownBy(() -> file.failUpload(null, LATER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failUpload_isOnlyPossibleWhileUploading() {
        SecureFile file = initiate("report.pdf", 1);
        file.completeUpload(DIGEST, 1, LATER);

        assertThatThrownBy(() -> file.failUpload(UploadFailureReason.TIMEOUT, LATER))
                .isInstanceOf(InvalidFileTransitionException.class);
    }

    // The reaper may fail an upload that was about to finish: the late completion must be refused.
    @Test
    void completeUpload_isRefusedOnceTheUploadHasFailed() {
        SecureFile file = initiate("report.pdf", 1);
        file.failUpload(UploadFailureReason.TIMEOUT, LATER);

        assertThatThrownBy(() -> file.completeUpload(DIGEST, 1, LATER))
                .isInstanceOf(InvalidFileTransitionException.class);
    }

    private static SecureFile pending() {
        SecureFile file = initiate("report.pdf", 1);
        file.completeUpload(DIGEST, 1, LATER);
        return file;
    }

    @Test
    void startScan_movesAPendingFileToScanning() {
        SecureFile file = pending();

        file.startScan(SCAN_START);

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCANNING);
        assertThat(file.getUpdatedAt()).isEqualTo(SCAN_START);
    }

    // The claim is a compare-and-set on the status: a second worker cannot start the same scan.
    @Test
    void startScan_isOnlyPossibleWhilePending() {
        SecureFile uploading = initiate("report.pdf", 1);
        SecureFile scanning = pending();
        scanning.startScan(SCAN_START);

        assertThatThrownBy(() -> uploading.startScan(SCAN_START)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> scanning.startScan(SCAN_START)).isInstanceOf(InvalidFileTransitionException.class);
    }

    private static SecureFile scanning() {
        SecureFile file = pending();
        file.startScan(SCAN_START);
        return file;
    }

    @Test
    void markClean_movesAScanningFileToClean() {
        SecureFile file = scanning();

        file.markClean(VERDICT_AT);

        assertThat(file.getStatus()).isEqualTo(FileStatus.CLEAN);
        assertThat(file.getInfectionSignature()).isEmpty();
        assertThat(file.getUpdatedAt()).isEqualTo(VERDICT_AT);
    }

    @Test
    void markInfected_movesAScanningFileToInfectedAndKeepsTheSignature() {
        SecureFile file = scanning();

        file.markInfected(SIGNATURE, VERDICT_AT);

        assertThat(file.getStatus()).isEqualTo(FileStatus.INFECTED);
        assertThat(file.getInfectionSignature()).contains(SIGNATURE);
        assertThat(file.getUpdatedAt()).isEqualTo(VERDICT_AT);
    }

    // The scan_verdict table requires the signature name for INFECTED.
    @Test
    void markInfected_requiresTheSignatureName() {
        SecureFile file = scanning();

        assertThatThrownBy(() -> file.markInfected(null, VERDICT_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> file.markInfected("  ", VERDICT_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(file.getStatus()).isEqualTo(FileStatus.SCANNING);
    }

    @Test
    void aVerdictIsOnlyPossibleWhileScanning() {
        SecureFile uploading = initiate("report.pdf", 1);
        SecureFile pending = pending();

        assertThatThrownBy(() -> uploading.markClean(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> pending.markClean(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> pending.markInfected(SIGNATURE, VERDICT_AT))
                .isInstanceOf(InvalidFileTransitionException.class);
    }

    // CLEAN and INFECTED are final: a verdict is never overwritten by a late or duplicate worker.
    @Test
    void aVerdictIsFinal() {
        SecureFile clean = scanning();
        clean.markClean(VERDICT_AT);
        SecureFile infected = scanning();
        infected.markInfected(SIGNATURE, VERDICT_AT);

        assertThatThrownBy(() -> clean.markInfected(SIGNATURE, VERDICT_AT))
                .isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> infected.markClean(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> clean.markClean(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> infected.markInfected(SIGNATURE, VERDICT_AT))
                .isInstanceOf(InvalidFileTransitionException.class);
    }

    @Test
    void failScan_movesAScanningFileToScanFailed() {
        SecureFile file = scanning();

        file.failScan(VERDICT_AT);

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCAN_FAILED);
        assertThat(file.getUpdatedAt()).isEqualTo(VERDICT_AT);
    }

    // SCAN_FAILED is terminal for the automatic processing: no worker picks the file up again by itself.
    @Test
    void failScan_isFinalForTheAutomaticProcessing() {
        SecureFile file = scanning();
        file.failScan(VERDICT_AT);

        assertThatThrownBy(() -> file.startScan(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> file.markClean(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
    }

    @Test
    void failScan_isOnlyPossibleWhileScanning() {
        SecureFile pending = pending();
        SecureFile clean = scanning();
        clean.markClean(VERDICT_AT);

        assertThatThrownBy(() -> pending.failScan(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> clean.failScan(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
    }

    // A worker that stops (ClamAV down, shutdown, expired lease) hands the file back without any verdict.
    @Test
    void requeueScan_putsAScanningFileBackToPending() {
        SecureFile file = scanning();

        file.requeueScan(VERDICT_AT);

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getUpdatedAt()).isEqualTo(VERDICT_AT);
        file.startScan(VERDICT_AT.plusSeconds(1));
        assertThat(file.getStatus()).isEqualTo(FileStatus.SCANNING);
    }

    @Test
    void requeueScan_isOnlyPossibleWhileScanning() {
        SecureFile pending = pending();
        SecureFile clean = scanning();
        clean.markClean(VERDICT_AT);

        assertThatThrownBy(() -> pending.requeueScan(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
        assertThatThrownBy(() -> clean.requeueScan(VERDICT_AT)).isInstanceOf(InvalidFileTransitionException.class);
    }
}