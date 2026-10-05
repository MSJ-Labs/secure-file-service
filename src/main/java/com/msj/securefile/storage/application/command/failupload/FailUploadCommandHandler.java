package com.msj.securefile.storage.application.command.failupload;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Records that an upload will never finish. It runs in its own transaction, apart from the one that raised the
 * error, so the failure is not rolled back with it.
 */
@Service
@RequiredArgsConstructor
public class FailUploadCommandHandler {

    private final FileRepository fileRepository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    @Transactional
    public void handle(FailUploadCommand command) {
        OwnerId owner = currentUserProvider.currentOwner();
        SecureFile file = fileRepository.findByIdAndOwner(command.fileId(), owner)
                .orElseThrow(SecureFileNotFoundException::new);

        file.failUpload(command.reason(), clock.instant());

        fileRepository.save(file, new Actor.User(owner));
    }
}