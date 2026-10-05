package com.msj.securefile.storage.application.command.initiateupload;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.IdGenerator;
import com.msj.securefile.storage.application.result.InitiatedUpload;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadTimeoutPolicy;
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
    private final Clock clock;

    @Transactional
    public InitiatedUpload handle(InitiateUploadCommand command) {
        Instant now = clock.instant();

        SecureFile file = SecureFile.initiate(
                idGenerator.nextFileId(),
                currentUserProvider.currentOwner(),
                command.name(),
                command.declaredSize(),
                now,
                uploadTimeoutPolicy.deadline(now, command.declaredSize())
        );

        fileRepository.save(file);
        return new InitiatedUpload(file.id().asString(), file.getUploadExpiresAt());
    }
}