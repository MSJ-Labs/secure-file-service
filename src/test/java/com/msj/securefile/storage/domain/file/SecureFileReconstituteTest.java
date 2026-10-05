package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.event.UploadCompleted;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SecureFileReconstituteTest {

    private static final Instant CREATED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant UPDATED = CREATED.plusSeconds(300);
    private static final Instant EXPIRES = CREATED.plusSeconds(3_600);
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));
    private static final String SIGNATURE = "Win.Test.EICAR_HDB-1";
    private static final FileId ID = FileId.of(42L);
    private static final OwnerId OWNER = OwnerId.of(7L);

    private static SecureFile storedInfectedFile() {
        return SecureFile.reconstitute(ID, OWNER, "report.pdf", 1_024, FileStatus.INFECTED, DIGEST, EXPIRES,
                null, SIGNATURE, CREATED, UPDATED, 5L);
    }

    private static SecureFile storedUploadingFile() {
        return SecureFile.reconstitute(ID, OWNER, "report.pdf", 1_024, FileStatus.UPLOADING, null, EXPIRES,
                null, null, CREATED, CREATED, 1L);
    }

    @Test
    void reconstitute_rebuildsTheStoredState() {
        SecureFile file = storedInfectedFile();

        assertThat(file.id()).isEqualTo(ID);
        assertThat(file.getOwner()).isEqualTo(OWNER);
        assertThat(file.getName()).isEqualTo("report.pdf");
        assertThat(file.getDeclaredSize()).isEqualTo(1_024);
        assertThat(file.getStatus()).isEqualTo(FileStatus.INFECTED);
        assertThat(file.getSha256()).contains(DIGEST);
        assertThat(file.getInfectionSignature()).contains(SIGNATURE);
        assertThat(file.getUploadExpiresAt()).isEqualTo(EXPIRES);
        assertThat(file.getCreatedAt()).isEqualTo(CREATED);
        assertThat(file.getUpdatedAt()).isEqualTo(UPDATED);
        assertThat(file.version()).isEqualTo(5L);
    }

    @Test
    void reconstitute_recordsNoEventBecauseNothingHappenedToTheFile() {
        assertThat(storedInfectedFile().pullDomainEvents()).isEmpty();
    }

    @Test
    void reconstitute_keepsTheAbsentValuesAbsent() {
        SecureFile file = storedUploadingFile();

        assertThat(file.getSha256()).isEmpty();
        assertThat(file.getFailureReason()).isEmpty();
        assertThat(file.getInfectionSignature()).isEmpty();
    }

    @Test
    void reconstitute_exposesTheFailureReasonOfAFailedUpload() {
        SecureFile file = SecureFile.reconstitute(ID, OWNER, "report.pdf", 1_024, FileStatus.UPLOAD_FAILED, null,
                EXPIRES, UploadFailureReason.TIMEOUT, null, CREATED, UPDATED, 2L);

        assertThat(file.getFailureReason()).contains(UploadFailureReason.TIMEOUT);
    }

    @Test
    void reconstitute_givesALiveAggregateThatKeepsItsLifecycle() {
        SecureFile file = storedUploadingFile();

        file.completeUpload(DIGEST, 1_024, UPDATED);

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.pullDomainEvents()).containsExactly(new UploadCompleted(ID, DIGEST, 1_024, UPDATED));
        // The version is the loaded one until the adapter adds the number of events.
        assertThat(file.version()).isEqualTo(1L);
    }
}