package com.msj.securefile.storage.application.command.completeupload;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.IdGenerator;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanQueuePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * TX2 of the upload: the file becomes PENDING and its scan job is created in the same transaction, so a PENDING
 * file always has a job.
 */
@Service
@RequiredArgsConstructor
public class CompleteUploadCommandHandler {

    private final FileRepository fileRepository;
    private final ScanJobRepository scanJobRepository;
    private final CurrentUserProvider currentUserProvider;
    private final IdGenerator idGenerator;
    private final ScanQueuePolicy scanQueuePolicy;
    private final Clock clock;

    @Transactional
    public void handle(CompleteUploadCommand command) {
        Instant now = clock.instant();

        OwnerId owner = currentUserProvider.currentOwner();
        SecureFile file = fileRepository.findByIdAndOwner(command.fileId(), owner)
                .orElseThrow(SecureFileNotFoundException::new);
        file.completeUpload(command.digest(), command.actualSize(), now);

        ScanJob job = ScanJob.create(
                idGenerator.nextScanJobId(),
                file.id(),
                scanQueuePolicy.queueFor(file.getDeclaredSize()),
                now
        );

        Actor actor = new Actor.User(owner);
        fileRepository.save(file, actor);
        scanJobRepository.save(job, actor);
    }
}