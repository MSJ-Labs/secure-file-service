package com.msj.securefile.storage.application.command.recordverdict;

import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanJobState;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordScanVerdictCommandHandlerTest {

    private static final Instant CLAIMED_AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = CLAIMED_AT.plusSeconds(20);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final String SIGNATURE = "Win.Test.EICAR_HDB-1";

    @Mock ScanJobRepository scanJobRepository;
    @Mock FileRepository fileRepository;

    private RecordScanVerdictCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RecordScanVerdictCommandHandler(scanJobRepository, fileRepository, CLOCK);
    }

    private static ScanJob leasedTo(WorkerId worker) {
        ScanJob job = ScanJob.create(JOB_ID, FILE_ID, ScanQueue.SMALL, CLAIMED_AT);
        job.claim(worker, CLAIMED_AT, Duration.ofSeconds(30));
        return job;
    }

    private static SecureFile scanningFile() {
        SecureFile file = SecureFile.initiate(FILE_ID, OwnerId.of(7L), "report.pdf", 1_000,
                CLAIMED_AT, CLAIMED_AT.plusSeconds(3_600));
        file.completeUpload(Sha256.of("a".repeat(64)), 1_000, CLAIMED_AT);
        file.startScan(CLAIMED_AT);
        return file;
    }

    private void givenAScanInProgress(WorkerId leaseOwner) {
        when(scanJobRepository.findById(JOB_ID)).thenReturn(Optional.of(leasedTo(leaseOwner)));
    }

    private void givenTheScannedFile() {
        when(fileRepository.findByIdForScan(FILE_ID)).thenReturn(Optional.of(scanningFile()));
    }

    @Test
    void handle_marksTheFileCleanAndSavesIt() {
        givenAScanInProgress(WORKER);
        givenTheScannedFile();

        handler.handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Clean()));

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FileStatus.CLEAN);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void handle_marksTheFileInfectedWithTheSignatureAndSavesIt() {
        givenAScanInProgress(WORKER);
        givenTheScannedFile();

        handler.handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Infected(SIGNATURE)));

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FileStatus.INFECTED);
        assertThat(saved.getValue().getInfectionSignature()).contains(SIGNATURE);
    }

    @Test
    void handle_completesTheJobWhateverTheVerdict() {
        givenAScanInProgress(WORKER);
        givenTheScannedFile();

        handler.handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Infected(SIGNATURE)));

        ArgumentCaptor<ScanJob> saved = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(ScanJobState.DONE);
    }

    @Test
    void handle_recordsNothingWhenAnotherWorkerHoldsTheLease() {
        givenAScanInProgress(WorkerId.of("worker-2"));

        assertThatThrownBy(() ->
                handler.handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Clean())))
                .isInstanceOf(LeaseLostException.class);

        verify(scanJobRepository, never()).save(any());
        verify(fileRepository, never()).save(any());
    }

    @Test
    void handle_tellsTheWorkerItLostTheLeaseWhenTheJobDoesNotExist() {
        when(scanJobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                handler.handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Clean())))
                .isInstanceOf(LeaseLostException.class);

        verify(fileRepository, never()).save(any());
    }
}