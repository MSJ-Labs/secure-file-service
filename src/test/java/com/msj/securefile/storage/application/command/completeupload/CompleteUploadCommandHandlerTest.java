package com.msj.securefile.storage.application.command.completeupload;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.IdGenerator;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.InvalidFileTransitionException;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanJobState;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.ScanQueuePolicy;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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
class CompleteUploadCommandHandlerTest {

    private static final Instant STARTED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = STARTED.plusSeconds(30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    // Round threshold so that 1_000 is SMALL and 1_001 is LARGE.
    private static final ScanQueuePolicy QUEUE_POLICY = new ScanQueuePolicy(1_000);
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));

    @Mock FileRepository fileRepository;
    @Mock ScanJobRepository scanJobRepository;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock IdGenerator idGenerator;

    private CompleteUploadCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CompleteUploadCommandHandler(
                fileRepository, scanJobRepository, currentUserProvider, idGenerator, QUEUE_POLICY, CLOCK);
    }

    private static SecureFile uploading(long declaredSize) {
        return SecureFile.initiate(FILE_ID, OWNER, "report.pdf", declaredSize, STARTED, STARTED.plusSeconds(3_600));
    }

    private void givenOwnedFile(SecureFile file) {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.of(file));
    }

    @Test
    void handle_movesTheFileToPendingWithItsDigestAndSavesIt() {
        givenOwnedFile(uploading(1_000));
        when(idGenerator.nextScanJobId()).thenReturn(JOB_ID);

        handler.handle(new CompleteUploadCommand(FILE_ID, DIGEST, 1_000));

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(saved.getValue().getSha256()).contains(DIGEST);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void handle_createsAPendingScanJobInTheSmallQueueForASmallFile() {
        givenOwnedFile(uploading(1_000));
        when(idGenerator.nextScanJobId()).thenReturn(JOB_ID);

        handler.handle(new CompleteUploadCommand(FILE_ID, DIGEST, 1_000));

        ArgumentCaptor<ScanJob> saved = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(JOB_ID);
        assertThat(saved.getValue().getFileId()).isEqualTo(FILE_ID);
        assertThat(saved.getValue().getQueue()).isEqualTo(ScanQueue.SMALL);
        assertThat(saved.getValue().getState()).isEqualTo(ScanJobState.PENDING);
    }

    @Test
    void handle_createsTheScanJobInTheLargeQueueForALargeFile() {
        givenOwnedFile(uploading(1_001));
        when(idGenerator.nextScanJobId()).thenReturn(JOB_ID);

        handler.handle(new CompleteUploadCommand(FILE_ID, DIGEST, 1_001));

        ArgumentCaptor<ScanJob> saved = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(saved.capture());
        assertThat(saved.getValue().getQueue()).isEqualTo(ScanQueue.LARGE);
    }

    @Test
    void handle_behavesAsNotFoundWhenTheFileIsNotTheCallers() {
        // The lookup is by id and owner: a file of someone else is indistinguishable from a missing one.
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new CompleteUploadCommand(FILE_ID, DIGEST, 1_000)))
                .isInstanceOf(SecureFileNotFoundException.class);

        verify(fileRepository, never()).save(any());
        verify(scanJobRepository, never()).save(any());
    }

    @Test
    void handle_createsNoScanJobWhenTheCompletionIsRefused() {
        SecureFile alreadyPending = uploading(1_000);
        alreadyPending.completeUpload(DIGEST, 1_000, STARTED);
        givenOwnedFile(alreadyPending);

        assertThatThrownBy(() -> handler.handle(new CompleteUploadCommand(FILE_ID, DIGEST, 1_000)))
                .isInstanceOf(InvalidFileTransitionException.class);

        verify(fileRepository, never()).save(any());
        verify(scanJobRepository, never()).save(any());
    }
}