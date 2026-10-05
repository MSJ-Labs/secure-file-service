package com.msj.securefile.storage.application.command.initiateupload;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.IdGenerator;
import com.msj.securefile.storage.application.result.InitiatedUpload;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadTimeoutPolicy;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class InitiateUploadCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    // 60 s + 1 MiB at 1 MiB/s = 61 s: round numbers for the deadline assertion.
    private static final UploadTimeoutPolicy POLICY = new UploadTimeoutPolicy(Duration.ofSeconds(60), 1_048_576);
    private static final OwnerId OWNER = OwnerId.of(7L);
    // The audit trail records who started the upload: the caller.
    private static final Actor ACTOR = new Actor.User(OWNER);
    private static final FileId FILE_ID = FileId.of(42L);

    @Mock FileRepository fileRepository;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock IdGenerator idGenerator;

    private InitiateUploadCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new InitiateUploadCommandHandler(fileRepository, currentUserProvider, idGenerator, POLICY, CLOCK);
    }

    @Test
    void handle_savesAnUploadingFileOwnedByTheCaller() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(idGenerator.nextFileId()).thenReturn(FILE_ID);

        handler.handle(new InitiateUploadCommand("report.pdf", 1_048_576));

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture(), eq(ACTOR));
        SecureFile file = saved.getValue();
        assertThat(file.id()).isEqualTo(FILE_ID);
        assertThat(file.getOwner()).isEqualTo(OWNER);
        assertThat(file.getName()).isEqualTo("report.pdf");
        assertThat(file.getDeclaredSize()).isEqualTo(1_048_576);
        assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOADING);
        assertThat(file.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void handle_setsTheDeadlineFromThePolicy() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(idGenerator.nextFileId()).thenReturn(FILE_ID);

        handler.handle(new InitiateUploadCommand("report.pdf", 1_048_576));

        ArgumentCaptor<SecureFile> saved = ArgumentCaptor.forClass(SecureFile.class);
        verify(fileRepository).save(saved.capture(), eq(ACTOR));
        assertThat(saved.getValue().getUploadExpiresAt()).isEqualTo(NOW.plusSeconds(61));
    }

    @Test
    void handle_returnsTheFileIdAsStringAndTheDeadline() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(idGenerator.nextFileId()).thenReturn(FILE_ID);

        InitiatedUpload result = handler.handle(new InitiateUploadCommand("report.pdf", 1_048_576));

        assertThat(result.fileId()).isEqualTo(FILE_ID.value().toString());
        assertThat(result.uploadExpiresAt()).isEqualTo(NOW.plusSeconds(61));
    }

    @Test
    void handle_savesNothingWhenTheNameIsBlank() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(idGenerator.nextFileId()).thenReturn(FILE_ID);

        assertThatThrownBy(() -> handler.handle(new InitiateUploadCommand(" ", 10)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(fileRepository, never()).save(any(), any());
    }
}
