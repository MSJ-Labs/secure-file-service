package com.msj.securefile.storage.application.command.failupload;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.exception.InvalidFileTransitionException;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
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
class FailUploadCommandHandlerTest {

    private static final Instant STARTED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = STARTED.plusSeconds(30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final FileId FILE_ID = FileId.of(42L);

    @Mock FileRepository fileRepository;
    @Mock CurrentUserProvider currentUserProvider;

    private FailUploadCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FailUploadCommandHandler(fileRepository, currentUserProvider, CLOCK);
    }

    private static SecureFile uploading() {
        return SecureFile.initiate(FILE_ID, OWNER, "report.pdf", 1_000, STARTED, STARTED.plusSeconds(3_600));
    }

    @Test
    void handle_failsTheUploadWithTheReasonAndSavesIt() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.of(uploading()));

        handler.handle(new FailUploadCommand(FILE_ID, UploadFailureReason.SIZE_MISMATCH));

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FileStatus.UPLOAD_FAILED);
        assertThat(saved.getValue().getFailureReason()).contains(UploadFailureReason.SIZE_MISMATCH);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void handle_behavesAsNotFoundWhenTheFileIsNotTheCallers() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new FailUploadCommand(FILE_ID, UploadFailureReason.ABORTED)))
                .isInstanceOf(SecureFileNotFoundException.class);

        verify(fileRepository, never()).save(any());
    }

    @Test
    void handle_refusesToFailAnUploadThatAlreadyCompleted() {
        SecureFile completed = uploading();
        completed.completeUpload(Sha256.of("a".repeat(64)), 1_000, STARTED);
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> handler.handle(new FailUploadCommand(FILE_ID, UploadFailureReason.STORAGE_ERROR)))
                .isInstanceOf(InvalidFileTransitionException.class);

        verify(fileRepository, never()).save(any());
    }
}