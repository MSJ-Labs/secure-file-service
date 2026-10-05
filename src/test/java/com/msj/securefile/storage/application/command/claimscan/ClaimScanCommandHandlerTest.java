package com.msj.securefile.storage.application.command.claimscan;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimScanCommandHandlerTest {

    private static final Instant CREATED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(60);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final ClaimScanCommand COMMAND = new ClaimScanCommand(ScanQueue.SMALL, WORKER, LEASE);

    @Mock ScanJobRepository scanJobRepository;
    @Mock FileRepository fileRepository;

    private ClaimScanCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ClaimScanCommandHandler(scanJobRepository, fileRepository, CLOCK);
    }

    private static SecureFile pendingFile() {
        SecureFile file = SecureFile.initiate(FILE_ID, OwnerId.of(7L), "report.pdf", 1_000,
                CREATED, CREATED.plusSeconds(3_600));
        file.completeUpload(Sha256.of("a".repeat(64)), 1_000, CREATED);
        return file;
    }

    private void givenADueJob() {
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW))
                .thenReturn(Optional.of(ScanJob.create(JOB_ID, FILE_ID, ScanQueue.SMALL, CREATED)));
        when(fileRepository.findByIdForScan(FILE_ID)).thenReturn(Optional.of(pendingFile()));
    }

    @Test
    void handle_leasesTheJobToTheWorkerAndSavesIt() {
        givenADueJob();

        handler.handle(COMMAND);

        ArgumentCaptor<ScanJob> saved = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(saved.getValue().getLeaseOwner()).contains(WORKER);
        assertThat(saved.getValue().getLeaseExpiresAt()).contains(NOW.plus(LEASE));
    }

    @Test
    void handle_movesTheFileToScanningAndSavesIt() {
        givenADueJob();

        handler.handle(COMMAND);

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FileStatus.SCANNING);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void handle_returnsWhatTheWorkerNeedsToScan() {
        givenADueJob();

        Optional<ClaimedScan> result = handler.handle(COMMAND);

        assertThat(result).contains(new ClaimedScan(JOB_ID, FILE_ID, NOW.plus(LEASE)));
    }

    @Test
    void handle_returnsNothingWhenNoJobIsDue() {
        when(scanJobRepository.findNextDue(ScanQueue.SMALL, NOW)).thenReturn(Optional.empty());

        Optional<ClaimedScan> result = handler.handle(COMMAND);

        assertThat(result).isEmpty();
        verify(scanJobRepository, never()).save(any());
        verify(fileRepository, never()).save(any());
    }
}