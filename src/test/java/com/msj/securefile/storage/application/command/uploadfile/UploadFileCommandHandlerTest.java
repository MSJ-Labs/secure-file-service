package com.msj.securefile.storage.application.command.uploadfile;

import com.msj.securefile.storage.application.command.completeupload.CompleteUploadCommand;
import com.msj.securefile.storage.application.command.completeupload.CompleteUploadCommandHandler;
import com.msj.securefile.storage.application.command.failupload.FailUploadCommand;
import com.msj.securefile.storage.application.command.failupload.FailUploadCommandHandler;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.FileStorageException;
import com.msj.securefile.storage.application.port.out.FileStoragePort;
import com.msj.securefile.storage.application.port.out.StorageZone;
import com.msj.securefile.storage.application.port.out.StoredContent;
import com.msj.securefile.storage.application.port.out.UploadInterruptedException;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.exception.FileNotUploadableException;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.exception.UploadSizeMismatchException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadFileCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));
    private static final StoredContent STORED = new StoredContent(1_000, DIGEST);
    private static final InputStream BODY = new ByteArrayInputStream(new byte[]{1, 2, 3});

    @Mock FileRepository fileRepository;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock FileStoragePort fileStoragePort;
    @Mock CompleteUploadCommandHandler completeUpload;
    @Mock FailUploadCommandHandler failUpload;

    private UploadFileCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new UploadFileCommandHandler(
                fileRepository, currentUserProvider, fileStoragePort, completeUpload, failUpload);
    }

    private static SecureFile uploading() {
        return SecureFile.initiate(FILE_ID, OWNER, "report.pdf", 1_000, NOW, NOW.plusSeconds(3_600));
    }

    private void givenTheCallersFile(SecureFile file) {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.of(file));
    }

    @Test
    void handle_storesTheBodyInQuarantineThenCompletesTheUploadWithTheMeasuredValues() {
        givenTheCallersFile(uploading());
        when(fileStoragePort.store(StorageZone.QUARANTINE, FILE_ID, BODY)).thenReturn(STORED);

        handler.handle(new UploadFileCommand(FILE_ID, BODY));

        InOrder order = inOrder(fileStoragePort, completeUpload);
        order.verify(fileStoragePort).store(StorageZone.QUARANTINE, FILE_ID, BODY);
        order.verify(completeUpload).handle(new CompleteUploadCommand(FILE_ID, DIGEST, 1_000));
        verify(failUpload, never()).handle(any());
    }

    @Test
    void handle_behavesAsNotFoundAndStoresNothingWhenTheFileIsNotTheCallers() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new UploadFileCommand(FILE_ID, BODY)))
                .isInstanceOf(SecureFileNotFoundException.class);

        verify(fileStoragePort, never()).store(any(), any(), any());
    }

    @Test
    void handle_storesNothingWhenTheFileDoesNotAcceptContent() {
        SecureFile pending = uploading();
        pending.completeUpload(DIGEST, 1_000, NOW);
        givenTheCallersFile(pending);

        assertThatThrownBy(() -> handler.handle(new UploadFileCommand(FILE_ID, BODY)))
                .isInstanceOf(FileNotUploadableException.class);

        verify(fileStoragePort, never()).store(any(), any(), any());
        verify(failUpload, never()).handle(any());
    }

    @Test
    void handle_failsTheUploadAndCleansTheQuarantineWhenTheStorageFails() {
        FileStorageException failure = new FileStorageException(new RuntimeException("disk full"));
        givenTheCallersFile(uploading());
        when(fileStoragePort.store(StorageZone.QUARANTINE, FILE_ID, BODY)).thenThrow(failure);

        assertThatThrownBy(() -> handler.handle(new UploadFileCommand(FILE_ID, BODY))).isSameAs(failure);

        verify(failUpload).handle(new FailUploadCommand(FILE_ID, UploadFailureReason.STORAGE_ERROR));
        verify(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);
        verify(completeUpload, never()).handle(any());
    }

    @Test
    void handle_failsTheUploadAndCleansTheQuarantineWhenTheSizeDoesNotMatch() {
        givenTheCallersFile(uploading());
        when(fileStoragePort.store(StorageZone.QUARANTINE, FILE_ID, BODY)).thenReturn(STORED);
        doThrow(new UploadSizeMismatchException()).when(completeUpload).handle(any());

        assertThatThrownBy(() -> handler.handle(new UploadFileCommand(FILE_ID, BODY)))
                .isInstanceOf(UploadSizeMismatchException.class);

        verify(failUpload).handle(new FailUploadCommand(FILE_ID, UploadFailureReason.SIZE_MISMATCH));
        verify(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);
    }

    @Test
    void handle_failsTheUploadAsAbortedAndCleansTheQuarantineWhenTheClientStopsSending() {
        UploadInterruptedException interruption = new UploadInterruptedException(new RuntimeException("reset"));
        givenTheCallersFile(uploading());
        when(fileStoragePort.store(StorageZone.QUARANTINE, FILE_ID, BODY)).thenThrow(interruption);

        assertThatThrownBy(() -> handler.handle(new UploadFileCommand(FILE_ID, BODY))).isSameAs(interruption);

        // Not a storage error: the client is to blame, which is what the failure reason tells.
        verify(failUpload).handle(new FailUploadCommand(FILE_ID, UploadFailureReason.ABORTED));
        verify(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);
        verify(completeUpload, never()).handle(any());
    }

    @Test
    void handle_reportsTheOriginalErrorEvenWhenTheCleanupFails() {
        FileStorageException failure = new FileStorageException(new RuntimeException("disk full"));
        givenTheCallersFile(uploading());
        when(fileStoragePort.store(StorageZone.QUARANTINE, FILE_ID, BODY)).thenThrow(failure);
        doThrow(new FileStorageException(new RuntimeException("delete failed")))
                .when(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);

        assertThatThrownBy(() -> handler.handle(new UploadFileCommand(FILE_ID, BODY))).isSameAs(failure);

        verify(failUpload).handle(new FailUploadCommand(FILE_ID, UploadFailureReason.STORAGE_ERROR));
    }
}