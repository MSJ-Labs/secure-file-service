package com.msj.securefile.storage.application.command.receivefile;

import com.msj.securefile.storage.application.command.initiateupload.InitiateUploadCommand;
import com.msj.securefile.storage.application.command.initiateupload.InitiateUploadCommandHandler;
import com.msj.securefile.storage.application.command.uploadfile.UploadFileCommand;
import com.msj.securefile.storage.application.command.uploadfile.UploadFileCommandHandler;
import com.msj.securefile.storage.application.result.InitiatedUpload;
import com.msj.securefile.storage.domain.file.exception.FileTooLargeException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiveFileCommandHandlerTest {

    private static final FileId FILE_ID = FileId.of(42L);
    private static final InitiatedUpload INITIATED = new InitiatedUpload(FILE_ID.asString(), Instant.parse("2026-10-04T11:00:00Z"));
    private static final InitiateUploadCommand INITIATE = new InitiateUploadCommand("report.pdf", 1_024);

    @Mock InitiateUploadCommandHandler initiateUpload;
    @Mock UploadFileCommandHandler uploadFile;

    private final InputStream content = new ByteArrayInputStream(new byte[0]);
    private ReceiveFileCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReceiveFileCommandHandler(initiateUpload, uploadFile);
    }

    @Test
    void handle_createsTheFileThenStreamsTheContentIntoIt() {
        when(initiateUpload.handle(INITIATE)).thenReturn(INITIATED);

        ReceivedFile received = handler.handle(new ReceiveFileCommand("report.pdf", 1_024, content));

        InOrder inOrder = inOrder(initiateUpload, uploadFile);
        inOrder.verify(initiateUpload).handle(INITIATE);
        inOrder.verify(uploadFile).handle(new UploadFileCommand(FILE_ID, content));
        assertThat(received.fileId()).isEqualTo(FILE_ID.asString());
    }

    @Test
    void handle_readsNothingWhenTheInitiationIsRefused() {
        when(initiateUpload.handle(INITIATE)).thenThrow(new FileTooLargeException());
        ReceiveFileCommand command = new ReceiveFileCommand("report.pdf", 1_024, content);

        // A file that is refused never costs a byte of the body, nor a row.
        assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(FileTooLargeException.class);

        verify(uploadFile, never()).handle(any());
    }
}
