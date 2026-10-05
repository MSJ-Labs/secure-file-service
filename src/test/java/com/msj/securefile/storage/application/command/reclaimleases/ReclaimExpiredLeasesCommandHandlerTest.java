package com.msj.securefile.storage.application.command.reclaimleases;

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
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReclaimExpiredLeasesCommandHandlerTest {

    private static final Instant CLAIMED_AT = Instant.parse("2026-10-04T10:00:00Z");
    // Well past the 30 s lease: the worker stopped heartbeating.
    private static final Instant NOW = CLAIMED_AT.plusSeconds(120);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int BATCH_SIZE = 50;
    // The dead worker cannot act: the reaper that takes the job back is the system itself.
    private static final Actor ACTOR = new Actor.System();
    private static final ScanRetryPolicy RETRYING = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));
    private static final ScanRetryPolicy ONE_SHOT = new ScanRetryPolicy(1, Duration.ofSeconds(10), Duration.ofMinutes(5));

    @Mock ScanJobRepository scanJobRepository;
    @Mock FileRepository fileRepository;
    @Captor ArgumentCaptor<Collection<ScanJob>> savedJobs;
    @Captor ArgumentCaptor<Collection<SecureFile>> savedFiles;

    private ReclaimExpiredLeasesCommandHandler handlerWith(ScanRetryPolicy policy) {
        return new ReclaimExpiredLeasesCommandHandler(scanJobRepository, fileRepository, policy, CLOCK);
    }

    private static ScanJob expiredJob(long jobId, long fileId) {
        ScanJob job = ScanJob.create(ScanJobId.of(jobId), FileId.of(fileId), ScanQueue.SMALL, CLAIMED_AT);
        job.claim(WorkerId.of("dead-worker"), CLAIMED_AT, Duration.ofSeconds(30));
        return job;
    }

    private static SecureFile scanningFile(long fileId) {
        SecureFile file = SecureFile.initiate(FileId.of(fileId), OwnerId.of(7L), "report.pdf", 1_000,
                CLAIMED_AT, CLAIMED_AT.plusSeconds(3_600));
        file.completeUpload(Sha256.of("a".repeat(64)), 1_000, CLAIMED_AT);
        file.startScan(CLAIMED_AT);
        return file;
    }

    private void givenExpiredLeaseOn(long jobId, long fileId) {
        when(scanJobRepository.findExpiredLeases(NOW, BATCH_SIZE)).thenReturn(List.of(expiredJob(jobId, fileId)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(fileId)))).thenReturn(List.of(scanningFile(fileId)));
    }

    @Test
    void handle_schedulesARetryAndHandsTheFileBackToTheQueue() {
        givenExpiredLeaseOn(99L, 42L);

        handlerWith(RETRYING).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJobs.getValue()).singleElement().satisfies(job -> {
            assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);
            // A worker that dies on a file consumes an attempt, so such a file is not retried forever.
            assertThat(job.getAttempts()).isEqualTo(1);
            assertThat(job.getLeaseOwner()).isEmpty();
        });
        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).singleElement()
                .satisfies(file -> assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING));
    }

    @Test
    void handle_givesUpAndFailsTheFileWhenTheAttemptsAreExhausted() {
        givenExpiredLeaseOn(99L, 42L);

        handlerWith(ONE_SHOT).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJobs.getValue()).singleElement()
                .satisfies(job -> assertThat(job.getState()).isEqualTo(ScanJobState.DONE));
        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).singleElement()
                .satisfies(file -> assertThat(file.getStatus()).isEqualTo(FileStatus.SCAN_FAILED));
    }

    @Test
    void handle_reclaimsTheWholeBatchWithOneFileQueryAndOneSaveEach() {
        when(scanJobRepository.findExpiredLeases(NOW, BATCH_SIZE))
                .thenReturn(List.of(expiredJob(1L, 11L), expiredJob(2L, 12L)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(11L), FileId.of(12L))))
                .thenReturn(List.of(scanningFile(11L), scanningFile(12L)));

        int reclaimed = handlerWith(RETRYING).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        assertThat(reclaimed).isEqualTo(2);
        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJobs.getValue()).hasSize(2);
        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).hasSize(2);
    }

    private static SecureFile waitingFile(long fileId) {
        SecureFile file = SecureFile.initiate(FileId.of(fileId), OwnerId.of(7L), "report.pdf", 1_000,
                CLAIMED_AT, CLAIMED_AT.plusSeconds(3_600));
        file.completeUpload(Sha256.of("a".repeat(64)), 1_000, CLAIMED_AT);
        return file;
    }

    private ScanJob savedJob(long jobId) {
        return savedJobs.getValue().stream()
                .filter(job -> job.id().equals(ScanJobId.of(jobId)))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void handle_reclaimsTheJobButLeavesAloneAFileThatIsNotBeingScanned() {
        when(scanJobRepository.findExpiredLeases(NOW, BATCH_SIZE))
                .thenReturn(List.of(expiredJob(1L, 11L), expiredJob(2L, 12L)));
        // The second file is not SCANNING although its job holds a lease: inconsistent data.
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(11L), FileId.of(12L))))
                .thenReturn(List.of(scanningFile(11L), waitingFile(12L)));

        int reclaimed = handlerWith(RETRYING).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        // Both jobs are taken back, so the sick one no longer sits on its dead lease, and the batch goes through.
        assertThat(reclaimed).isEqualTo(2);
        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJob(1L).getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(savedJob(2L).getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(savedJob(2L).getAttempts()).isEqualTo(1);
        // Only the healthy file is written: nobody knows what state the other one should be in.
        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).extracting(SecureFile::id).containsExactly(FileId.of(11L));
    }

    @Test
    void handle_treatsAJobWithoutFileTheSameWay() {
        when(scanJobRepository.findExpiredLeases(NOW, BATCH_SIZE))
                .thenReturn(List.of(expiredJob(1L, 11L), expiredJob(2L, 12L)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(11L), FileId.of(12L))))
                .thenReturn(List.of(scanningFile(11L)));

        int reclaimed = handlerWith(RETRYING).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        assertThat(reclaimed).isEqualTo(2);
        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJob(2L).getState()).isEqualTo(ScanJobState.PENDING);
        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).extracting(SecureFile::id).containsExactly(FileId.of(11L));
    }

    @Test
    void handle_writesNoFileWhenNoneOfTheBatchIsBeingScanned() {
        when(scanJobRepository.findExpiredLeases(NOW, BATCH_SIZE)).thenReturn(List.of(expiredJob(2L, 12L)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(12L)))).thenReturn(List.of(waitingFile(12L)));

        handlerWith(RETRYING).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        verify(scanJobRepository).saveAll(any(), eq(ACTOR));
        verify(fileRepository, never()).saveAll(any(), any());
    }

    @Test
    void handle_doesNothingWhenNoLeaseIsExpired() {
        when(scanJobRepository.findExpiredLeases(NOW, BATCH_SIZE)).thenReturn(List.of());

        int reclaimed = handlerWith(RETRYING).handle(new ReclaimExpiredLeasesCommand(BATCH_SIZE));

        assertThat(reclaimed).isZero();
        verify(fileRepository, never()).findAllByIdForScan(any());
        verify(scanJobRepository, never()).saveAll(any(), any());
        verify(fileRepository, never()).saveAll(any(), any());
    }
}
