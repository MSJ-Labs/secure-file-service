package com.msj.securefile.storage.application.command.reapuploads;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Fails the uploads that stayed UPLOADING past their deadline (client gone, instance crashed). Nothing else would
 * ever leave them in that state.
 */
@Service
@RequiredArgsConstructor
public class ReapExpiredUploadsCommandHandler {

    private final FileRepository fileRepository;
    private final Clock clock;

    @Transactional
    public int handle(ReapExpiredUploadsCommand command) {
        Instant now = clock.instant();

        // The reaper acts for nobody: the audit trail records the system itself.
        Actor actor = new Actor.System();

        List<SecureFile> expired = fileRepository.findExpiredUploads(now, command.batchSize());
        for (SecureFile file : expired) {
            file.failUpload(UploadFailureReason.TIMEOUT, now);
            fileRepository.save(file, actor);
        }
        return expired.size();
    }
}