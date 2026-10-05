package com.msj.securefile.storage.application.query.downloadfile;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.FileStoragePort;
import com.msj.securefile.storage.application.port.out.StorageZone;
import com.msj.securefile.storage.application.result.FileDownload;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.FileNotDownloadableException;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadFileQueryHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final DownloadFileQuery QUERY = new DownloadFileQuery(FILE_ID);

    @Mock FileRepository fileRepository;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock FileStoragePort fileStoragePort;

    private DownloadFileQueryHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DownloadFileQueryHandler(fileRepository, currentUserProvider, fileStoragePort);
    }

    private static SecureFile pending() {
        SecureFile file = SecureFile.initiate(FILE_ID, OWNER, "report.pdf", 1_000, NOW, NOW.plusSeconds(3_600));
        file.completeUpload(Sha256.of("a".repeat(64)), 1_000, NOW);
        return file;
    }

    private static SecureFile clean() {
        SecureFile file = pending();
        file.startScan(NOW);
        file.markClean(NOW);
        return file;
    }

    private void givenTheCallersFile(SecureFile file) {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.of(file));
    }

    @Test
    void handle_streamsTheContentOfACleanFileFromTheCleanZone() {
        InputStream content = new ByteArrayInputStream(new byte[]{1, 2, 3});
        givenTheCallersFile(clean());
        when(fileStoragePort.open(StorageZone.CLEAN, FILE_ID)).thenReturn(content);

        FileDownload result = handler.handle(QUERY);

        assertThat(result.name()).isEqualTo("report.pdf");
        assertThat(result.size()).isEqualTo(1_000);
        assertThat(result.content()).isSameAs(content);
    }

    @Test
    void handle_behavesAsNotFoundWhenTheFileIsNotTheCallers() {
        when(currentUserProvider.currentOwner()).thenReturn(OWNER);
        when(fileRepository.findByIdAndOwner(FILE_ID, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(QUERY)).isInstanceOf(SecureFileNotFoundException.class);

        verify(fileStoragePort, never()).open(any(), any());
    }

    @Test
    void handle_neverOpensTheStorageForAFileThatIsNotClean() {
        givenTheCallersFile(pending());

        assertThatThrownBy(() -> handler.handle(QUERY)).isInstanceOf(FileNotDownloadableException.class);

        verify(fileStoragePort, never()).open(any(), any());
    }
}