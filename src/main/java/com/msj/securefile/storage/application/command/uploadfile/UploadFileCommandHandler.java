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
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.exception.UploadSizeMismatchException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Streams the body of an upload. Not transactional on purpose: no database connection is held while the body
 * flows. The two transactions around it (the row before, the completion after) belong to the handlers it calls,
 * and a failure is recorded by FailUpload in a transaction of its own so it is not rolled back with the error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadFileCommandHandler {

    private final FileRepository fileRepository;
    private final CurrentUserProvider currentUserProvider;
    private final FileStoragePort fileStoragePort;
    private final CompleteUploadCommandHandler completeUpload;
    private final FailUploadCommandHandler failUpload;

    public void handle(UploadFileCommand command) {
        FileId fileId = command.fileId();
        SecureFile file = fileRepository.findByIdAndOwner(fileId, currentUserProvider.currentOwner())
                .orElseThrow(SecureFileNotFoundException::new);
        file.ensureUploadable();

        StoredContent stored = store(command);
        complete(fileId, stored);
    }

    private StoredContent store(UploadFileCommand command) {
        try {
            return fileStoragePort.store(StorageZone.QUARANTINE, command.fileId(), command.content());
        } catch (UploadInterruptedException e) {
            abandon(command.fileId(), UploadFailureReason.ABORTED);
            throw e;
        } catch (FileStorageException e) {
            abandon(command.fileId(), UploadFailureReason.STORAGE_ERROR);
            throw e;
        }
    }

    private void complete(FileId fileId, StoredContent stored) {
        try {
            completeUpload.handle(new CompleteUploadCommand(fileId, stored.digest(), stored.size()));
        } catch (UploadSizeMismatchException e) {
            abandon(fileId, UploadFailureReason.SIZE_MISMATCH);
            throw e;
        }
    }

    // Records the failure first, then removes what was written. The cleanup is best effort: it must never hide the
    // error that is being reported.
    private void abandon(FileId fileId, UploadFailureReason reason) {
        failUpload.handle(new FailUploadCommand(fileId, reason));
        try {
            fileStoragePort.delete(StorageZone.QUARANTINE, fileId);
        } catch (FileStorageException e) {
            log.warn("Could not remove the quarantined content of file {}", fileId.asString(), e);
        }
    }
}