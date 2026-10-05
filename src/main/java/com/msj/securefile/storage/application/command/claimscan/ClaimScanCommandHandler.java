package com.msj.securefile.storage.application.command.claimscan;

import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.application.result.ClaimedScan;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.scan.ScanJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A worker takes the next due job of its queue. The job and the file change state in one short transaction; the scan
 * itself runs outside of it, protected by the lease.
 */
@Service
@RequiredArgsConstructor
public class ClaimScanCommandHandler {

    private final ScanJobRepository scanJobRepository;
    private final FileRepository fileRepository;
    private final Clock clock;

    @Transactional
    public Optional<ClaimedScan> handle(ClaimScanCommand command) {
        Instant now = clock.instant();

        return scanJobRepository.findNextDue(command.queue(), now).map(job -> claim(job, command, now));
    }

    private ClaimedScan claim(ScanJob job, ClaimScanCommand command, Instant now) {
        SecureFile file = fileRepository.findByIdForScan(job.getFileId())
                .orElseThrow(SecureFileNotFoundException::new);

        job.claim(command.worker(), now, command.lease());
        file.startScan(now);

        scanJobRepository.save(job);
        fileRepository.save(file);
        return new ClaimedScan(job.id(), job.getFileId(), now.plus(command.lease()));
    }
}