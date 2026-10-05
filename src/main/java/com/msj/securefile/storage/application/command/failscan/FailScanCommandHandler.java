package com.msj.securefile.storage.application.command.failscan;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.scan.ScanFailureOutcome;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The worker reports that the scan itself failed. The job decides between a retry and giving up; the file follows
 * that decision in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class FailScanCommandHandler {

    private final ScanJobRepository scanJobRepository;
    private final FileRepository fileRepository;
    private final ScanRetryPolicy scanRetryPolicy;
    private final Clock clock;

    @Transactional
    public void handle(FailScanCommand command) {
        Instant now = clock.instant();

        ScanJob job = scanJobRepository.findById(command.jobId()).orElseThrow(LeaseLostException::new);
        ScanFailureOutcome outcome = job.fail(command.worker(), command.error(), now, scanRetryPolicy);

        SecureFile file = fileRepository.findByIdForScan(job.getFileId())
                .orElseThrow(SecureFileNotFoundException::new);
        switch (outcome) {
            case RETRY_SCHEDULED -> file.requeueScan(now);
            case EXHAUSTED -> file.failScan(now);
        }

        Actor actor = new Actor.Worker(command.worker());
        scanJobRepository.save(job, actor);
        fileRepository.save(file, actor);
    }
}