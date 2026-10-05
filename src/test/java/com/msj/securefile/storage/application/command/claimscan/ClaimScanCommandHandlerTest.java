package com.msj.securefile.storage.application.command.claimscan;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.application.result.ClaimedScan;
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
import org.junit.jupiter.api.BeforeEach;
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
class ClaimScanCommandHandlerTest {

    private static final Instant CREATED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(60);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final int LIMIT = 3;
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final Actor ACTOR = new Actor.Worker(WORKER);
    private static final ClaimScanCommand COMMAND = new ClaimScanCommand(ScanQueue.SMALL, WORKER, LEASE, LIMIT);
    private static final ScanRetryPolicy RETRYING = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));
    private static final ScanRetryPolicy ONE_SHOT = new ScanRetryPolicy(1, Duration.ofSeconds(10), Duration.ofMinutes(5));

    @Mock ScanJobRepository scanJobRepository;
    @Mock FileRepository fileRepository;
    @Captor ArgumentCaptor<Collection<ScanJob>> savedJobs;
    @Captor ArgumentCaptor<Collection<SecureFile>> savedFiles;

    private ClaimScanCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ClaimScanCommandHandler(scanJobRepository, fileRepository, RETRYING, CLOCK);
    }

    private static ScanJob dueJob(long jobId, long fileId) {
        return ScanJob.create(ScanJobId.of(jobId), FileId.of(fileId), ScanQueue.SMALL, CREATED);
    }

    private static SecureFile pendingFile(long fileId) {
        SecureFile file = SecureFile.initiate(FileId.of(fileId), OwnerId.of(7L), "report.pdf", 1_000,
                CREATED, CREATED.plusSeconds(3_600));
        file.completeUpload(Sha256.of("a".repeat(64)), 1_000, CREATED);
        return file;
    }

    // Two due jobs in one batch, the limit being larger: the worker has free slots left over.
    private void givenTwoDueJobs() {
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW, LIMIT))
                .thenReturn(List.of(dueJob(91L, 1L), dueJob(92L, 2L)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(1L), FileId.of(2L))))
                .thenReturn(List.of(pendingFile(1L), pendingFile(2L)));
    }

    @Test
    void handle_leasesEveryJobOfTheBatchToTheWorkerAndSavesThemTogether() {
        givenTwoDueJobs();

        handler.handle(COMMAND);

        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJobs.getValue()).hasSize(2).allSatisfy(job -> {
            assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
            assertThat(job.getLeaseOwner()).contains(WORKER);
            assertThat(job.getLeaseExpiresAt()).contains(NOW.plus(LEASE));
        });
    }

    @Test
    void handle_movesTheFilesToScanningWithOneQueryAndOneSave() {
        givenTwoDueJobs();

        handler.handle(COMMAND);

        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).hasSize(2).allSatisfy(file -> {
            assertThat(file.getStatus()).isEqualTo(FileStatus.SCANNING);
            assertThat(file.getUpdatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void handle_returnsWhatTheWorkerNeedsToScanForEachJobInOrder() {
        givenTwoDueJobs();

        List<ClaimedScan> result = handler.handle(COMMAND);

        assertThat(result).containsExactly(
                new ClaimedScan(ScanJobId.of(91L), FileId.of(1L), NOW.plus(LEASE)),
                new ClaimedScan(ScanJobId.of(92L), FileId.of(2L), NOW.plus(LEASE)));
    }

    @Test
    void handle_returnsNothingAndTouchesNothingWhenNoJobIsDue() {
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW, LIMIT)).thenReturn(List.of());

        List<ClaimedScan> result = handler.handle(COMMAND);

        assertThat(result).isEmpty();
        verify(fileRepository, never()).findAllByIdForScan(any());
        verify(scanJobRepository, never()).saveAll(any(), any());
        verify(fileRepository, never()).saveAll(any(), any());
    }

    private static SecureFile scanningFile(long fileId) {
        SecureFile file = pendingFile(fileId);
        file.startScan(CREATED);
        return file;
    }

    private ScanJob savedJob(long jobId) {
        return savedJobs.getValue().stream()
                .filter(job -> job.id().equals(ScanJobId.of(jobId)))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void handle_setsAsideAJobWhoseFileIsNotWaitingForAScanAndClaimsTheOthers() {
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW, LIMIT))
                .thenReturn(List.of(dueJob(91L, 1L), dueJob(92L, 2L)));
        // The second file is already being scanned: a job waiting for a file that is not waiting is inconsistent data.
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(1L), FileId.of(2L))))
                .thenReturn(List.of(pendingFile(1L), scanningFile(2L)));

        List<ClaimedScan> result = handler.handle(COMMAND);

        // The healthy job is claimed and returned: one sick job must not stop the others.
        assertThat(result).extracting(ClaimedScan::jobId).containsExactly(ScanJobId.of(91L));
        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJobs.getValue()).hasSize(2);
        assertThat(savedJob(91L).getState()).isEqualTo(ScanJobState.LEASED);
        // The sick job counts a failed attempt and waits for its backoff, so it no longer blocks the head of the queue.
        ScanJob sick = savedJob(92L);
        assertThat(sick.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(sick.getAttempts()).isEqualTo(1);
        assertThat(sick.getLastError()).isPresent();
        assertThat(sick.getNextAttemptAt()).isAfter(NOW);
        assertThat(sick.getLeaseOwner()).isEmpty();
        // Its file is left alone: nobody knows what state it should be in.
        verify(fileRepository).saveAll(savedFiles.capture(), eq(ACTOR));
        assertThat(savedFiles.getValue()).extracting(SecureFile::id).containsExactly(FileId.of(1L));
    }

    @Test
    void handle_treatsAJobWithoutFileAsASickJobToo() {
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW, LIMIT))
                .thenReturn(List.of(dueJob(91L, 1L), dueJob(92L, 2L)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(1L), FileId.of(2L))))
                .thenReturn(List.of(pendingFile(1L)));

        List<ClaimedScan> result = handler.handle(COMMAND);

        assertThat(result).extracting(ClaimedScan::jobId).containsExactly(ScanJobId.of(91L));
        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJob(92L).getAttempts()).isEqualTo(1);
    }

    @Test
    void handle_givesUpOnASickJobWhenItsAttemptsAreExhausted() {
        ClaimScanCommandHandler oneShot = new ClaimScanCommandHandler(scanJobRepository, fileRepository, ONE_SHOT, CLOCK);
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW, LIMIT)).thenReturn(List.of(dueJob(92L, 2L)));
        when(fileRepository.findAllByIdForScan(List.of(FileId.of(2L)))).thenReturn(List.of(scanningFile(2L)));

        List<ClaimedScan> result = oneShot.handle(COMMAND);

        assertThat(result).isEmpty();
        verify(scanJobRepository).saveAll(savedJobs.capture(), eq(ACTOR));
        assertThat(savedJob(92L).getState()).isEqualTo(ScanJobState.DONE);
        verify(fileRepository, never()).saveAll(any(), any());
    }
}
