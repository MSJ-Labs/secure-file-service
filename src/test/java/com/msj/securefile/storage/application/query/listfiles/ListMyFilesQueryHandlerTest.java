package com.msj.securefile.storage.application.query.listfiles;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.result.FileSummary;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListMyFilesQueryHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final OwnerId OWNER = OwnerId.of(7L);

    @Mock FileRepository fileRepository;
    @Mock CurrentUserProvider currentUserProvider;

    private ListMyFilesQueryHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ListMyFilesQueryHandler(fileRepository, currentUserProvider);
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
    }

    @Test
    void handle_describesEachFileOfTheCallerInTheOrderOfTheRepository() {
        SecureFile newer = SecureFile.initiate(FileId.of(2L), OWNER, "new.pdf", 2_000, NOW, NOW.plusSeconds(60));
        SecureFile older = SecureFile.initiate(FileId.of(1L), OWNER, "old.pdf", 1_000, NOW.minusSeconds(5),
                NOW.plusSeconds(60));
        when(fileRepository.findAllByOwner(OWNER)).thenReturn(List.of(newer, older));

        List<FileSummary> summaries = handler.handle();

        assertThat(summaries).containsExactly(
                new FileSummary(FileId.of(2L).asString(), "new.pdf", 2_000, FileStatus.UPLOADING, NOW),
                new FileSummary(FileId.of(1L).asString(), "old.pdf", 1_000, FileStatus.UPLOADING, NOW.minusSeconds(5)));
    }

    @Test
    void handle_isEmptyWhenTheCallerHasNoFile() {
        when(fileRepository.findAllByOwner(OWNER)).thenReturn(List.of());

        assertThat(handler.handle()).isEmpty();
    }
}
