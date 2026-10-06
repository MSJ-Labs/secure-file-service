package com.msj.securefile.storage.application.command.recordverdict;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Ends a scan: the verdict goes to the file and the job is done, in one transaction. The job is completed first, so a
 * worker that lost its lease is refused before the file is touched.
 */
@Service
@RequiredArgsConstructor
public class RecordScanVerdictCommandHandler {

    private final ScanJobRepository scanJobRepository;
    private final FileRepository fileRepository;
    private final Clock clock;

    @Transactional
    public void handle(RecordScanVerdictCommand command) {
        Instant now = clock.instant();

        ScanJob job = scanJobRepository.findById(command.jobId()).orElseThrow(LeaseLostException::new);
        job.complete(command.worker(), now);

        SecureFile file = fileRepository.findByIdForScan(job.getFileId())
                .orElseThrow(SecureFileNotFoundException::new);
        switch (command.verdict()) {
            case ScanVerdict.Clean() -> file.markClean(now);
            case ScanVerdict.Infected(String signature) -> file.markInfected(signature, now);
        }

        Actor actor = new Actor.Worker(command.worker());
        scanJobRepository.save(job, actor);
        fileRepository.save(file, actor);
    }
}