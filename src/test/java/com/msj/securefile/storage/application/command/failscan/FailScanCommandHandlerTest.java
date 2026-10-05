package com.msj.securefile.storage.application.command.failscan;

import com.msj.securefile.storage.application.port.out.Actor;
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
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FailScanCommandHandlerTest {

    private static final Instant CLAIMED_AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = CLAIMED_AT.plusSeconds(20);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final Actor ACTOR = new Actor.Worker(WORKER);
    private static final String ERROR = "clamd timed out";
    private static final ScanRetryPolicy RETRYING = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));
    private static final ScanRetryPolicy ONE_SHOT = new ScanRetryPolicy(1, Duration.ofSeconds(10), Duration.ofMinutes(5));

    @Mock ScanJobRepository scanJobRepository;
    @Mock FileRepository fileRepository;

    private FailScanCommandHandler handlerWith(ScanRetryPolicy policy) {
        return new FailScanCommandHandler(scanJobRepository, fileRepository, policy, CLOCK);
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
    void handle_schedulesARetryAndHandsTheFileBackToTheQueue() {
        givenAScanInProgress(WORKER);
        givenTheScannedFile();

        handlerWith(RETRYING).handle(new FailScanCommand(JOB_ID, WORKER, ERROR));

        ArgumentCaptor<ScanJob> savedJob = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(savedJob.capture(), eq(ACTOR));
        assertThat(savedJob.getValue().getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(savedJob.getValue().getAttempts()).isEqualTo(1);
        assertThat(savedJob.getValue().getNextAttemptAt()).isEqualTo(NOW.plus(RETRYING.backoff(1)));
        assertThat(savedJob.getValue().getLastError()).contains(ERROR);

        ArgumentCaptor<SecureFile> savedFile = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(savedFile.capture(), eq(ACTOR));
        assertThat(savedFile.getValue().getStatus()).isEqualTo(FileStatus.PENDING);
    }

    @Test
    void handle_givesUpAndFailsTheFileWhenTheAttemptsAreExhausted() {
        givenAScanInProgress(WORKER);
        givenTheScannedFile();

        handlerWith(ONE_SHOT).handle(new FailScanCommand(JOB_ID, WORKER, ERROR));

        ArgumentCaptor<ScanJob> savedJob = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(savedJob.capture(), eq(ACTOR));
        assertThat(savedJob.getValue().getState()).isEqualTo(ScanJobState.DONE);

        ArgumentCaptor<SecureFile> savedFile = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(savedFile.capture(), eq(ACTOR));
        assertThat(savedFile.getValue().getStatus()).isEqualTo(FileStatus.SCAN_FAILED);
    }

    @Test
    void handle_recordsNothingWhenAnotherWorkerHoldsTheLease() {
        givenAScanInProgress(WorkerId.of("worker-2"));

        assertThatThrownBy(() -> handlerWith(RETRYING).handle(new FailScanCommand(JOB_ID, WORKER, ERROR)))
                .isInstanceOf(LeaseLostException.class);

        verify(scanJobRepository, never()).save(any(), any());
        verify(fileRepository, never()).save(any(), any());
    }

    @Test
    void handle_tellsTheWorkerItLostTheLeaseWhenTheJobDoesNotExist() {
        when(scanJobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handlerWith(RETRYING).handle(new FailScanCommand(JOB_ID, WORKER, ERROR)))
                .isInstanceOf(LeaseLostException.class);

        verify(fileRepository, never()).save(any(), any());
    }
}