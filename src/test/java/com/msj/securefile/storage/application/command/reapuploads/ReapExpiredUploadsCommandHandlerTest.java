package com.msj.securefile.storage.application.command.reapuploads;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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
class ReapExpiredUploadsCommandHandlerTest {

    private static final Instant STARTED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = STARTED.plusSeconds(7_200);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OwnerId OWNER = OwnerId.of(7L);
    // The reaper acts for nobody: the audit trail records the system itself.
    private static final Actor ACTOR = new Actor.System();
    private static final int BATCH_SIZE = 50;

    @Mock FileRepository fileRepository;
    @Captor ArgumentCaptor<Collection<SecureFile>> saved;

    private ReapExpiredUploadsCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReapExpiredUploadsCommandHandler(fileRepository, CLOCK);
    }

    private static SecureFile expiredUpload(long id) {
        return SecureFile.initiate(FileId.of(id), OWNER, "report.pdf", 1_000, STARTED, STARTED.plusSeconds(3_600));
    }

    @Test
    void handle_failsEachExpiredUploadWithATimeoutAndSavesThemTogether() {
        when(fileRepository.findExpiredUploads(NOW, BATCH_SIZE))
                .thenReturn(List.of(expiredUpload(1L), expiredUpload(2L)));

        handler.handle(new ReapExpiredUploadsCommand(BATCH_SIZE));

        // One batch write for the whole batch, not one write per file.
        verify(fileRepository).saveAll(saved.capture(), eq(ACTOR));
        assertThat(saved.getValue()).hasSize(2).allSatisfy(file -> {
            assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOAD_FAILED);
            assertThat(file.getFailureReason()).contains(UploadFailureReason.TIMEOUT);
            assertThat(file.getUpdatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void handle_returnsTheNumberOfReapedUploads() {
        when(fileRepository.findExpiredUploads(NOW, BATCH_SIZE))
                .thenReturn(List.of(expiredUpload(1L), expiredUpload(2L), expiredUpload(3L)));

        int reaped = handler.handle(new ReapExpiredUploadsCommand(BATCH_SIZE));

        assertThat(reaped).isEqualTo(3);
    }

    @Test
    void handle_doesNothingWhenNoUploadIsExpired() {
        when(fileRepository.findExpiredUploads(NOW, BATCH_SIZE)).thenReturn(List.of());

        int reaped = handler.handle(new ReapExpiredUploadsCommand(BATCH_SIZE));

        assertThat(reaped).isZero();
        verify(fileRepository, never()).saveAll(any(), any());
    }
}
