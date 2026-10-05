package com.msj.securefile.storage.application.command.releasescan;

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
 * The worker gives the job back through no fault of the file (scanner unreachable, shutdown). No attempt is consumed.
 */
@Service
@RequiredArgsConstructor
public class ReleaseScanCommandHandler {

    private final ScanJobRepository scanJobRepository;
    private final FileRepository fileRepository;
    private final Clock clock;

    @Transactional
    public void handle(ReleaseScanCommand command) {
        Instant now = clock.instant();

        ScanJob job = scanJobRepository.findById(command.jobId()).orElseThrow(LeaseLostException::new);
        job.release(command.worker(), now, command.delay());

        SecureFile file = fileRepository.findByIdForScan(job.getFileId())
                .orElseThrow(SecureFileNotFoundException::new);
        file.requeueScan(now);

        scanJobRepository.save(job);
        fileRepository.save(file);
    }
}