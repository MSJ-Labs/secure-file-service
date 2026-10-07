package com.msj.securefile.storage.application.command.receivefile;

import com.msj.securefile.storage.application.command.initiateupload.InitiateUploadCommand;
import com.msj.securefile.storage.application.command.initiateupload.InitiateUploadCommandHandler;
import com.msj.securefile.storage.application.command.uploadfile.UploadFileCommand;
import com.msj.securefile.storage.application.command.uploadfile.UploadFileCommandHandler;
import com.msj.securefile.storage.application.result.InitiatedUpload;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The whole upload as one use case: the row first, then the body streamed into it. Not transactional on purpose, like
 * the upload it calls: each step owns its short transaction and none stays open while the body flows.
 */
@Service
@RequiredArgsConstructor
public class ReceiveFileCommandHandler {

    private final InitiateUploadCommandHandler initiateUpload;
    private final UploadFileCommandHandler uploadFile;

    public ReceivedFile handle(ReceiveFileCommand command) {
        InitiatedUpload initiated = initiateUpload.handle(new InitiateUploadCommand(command.name(), command.declaredSize()));

        uploadFile.handle(new UploadFileCommand(FileId.parse(initiated.fileId()), command.content()));
        return new ReceivedFile(initiated.fileId());
    }
}
