package com.msj.securefile.storage.application.command.initiateupload;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.IdGenerator;
import com.msj.securefile.storage.application.result.InitiatedUpload;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadSizePolicy;
import com.msj.securefile.storage.domain.file.UploadTimeoutPolicy;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * TX1 of the upload: the row exists before the body is streamed, and no transaction stays open during the stream.
 */
@Service
@RequiredArgsConstructor
public class InitiateUploadCommandHandler {

    private final FileRepository fileRepository;
    private final CurrentUserProvider currentUserProvider;
    private final IdGenerator idGenerator;
    private final UploadTimeoutPolicy uploadTimeoutPolicy;
    private final UploadSizePolicy uploadSizePolicy;
    private final Clock clock;

    @Transactional
    public InitiatedUpload handle(InitiateUploadCommand command) {
        // First of all: a file that is too large must not cost anything, not even a lookup of the caller.
        uploadSizePolicy.ensureAllowed(command.declaredSize());

        Instant now = clock.instant();

        OwnerId owner = currentUserProvider.currentOwner();

        SecureFile file = SecureFile.initiate(
                idGenerator.nextFileId(),
                owner,
                command.name(),
                command.declaredSize(),
                now,
                uploadTimeoutPolicy.deadline(now, command.declaredSize())
        );

        fileRepository.save(file, new Actor.User(owner));
        return new InitiatedUpload(file.id().asString(), file.getUploadExpiresAt());
    }
}