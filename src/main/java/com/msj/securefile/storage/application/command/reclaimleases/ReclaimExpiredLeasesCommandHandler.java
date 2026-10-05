package com.msj.securefile.storage.application.command.reclaimleases;

import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.scan.ScanFailureOutcome;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Takes back the jobs whose worker stopped heartbeating (crash, network cut). Without it such a job would stay LEASED
 * forever and its file SCANNING.
 */
@Service
@RequiredArgsConstructor
public class ReclaimExpiredLeasesCommandHandler {

    private final ScanJobRepository scanJobRepository;
    private final FileRepository fileRepository;
    private final ScanRetryPolicy scanRetryPolicy;
    private final Clock clock;

    @Transactional
    public int handle(ReclaimExpiredLeasesCommand command) {
        Instant now = clock.instant();

        List<ScanJob> expired = scanJobRepository.findExpiredLeases(now, command.batchSize());
        for (ScanJob job : expired) {
            reclaim(job, now);
        }
        return expired.size();
    }

    private void reclaim(ScanJob job, Instant now) {
        ScanFailureOutcome outcome = job.reclaimExpired(now, scanRetryPolicy);

        SecureFile file = fileRepository.findByIdForScan(job.getFileId())
                .orElseThrow(SecureFileNotFoundException::new);
        switch (outcome) {
            case RETRY_SCHEDULED -> file.requeueScan(now);
            case EXHAUSTED -> file.failScan(now);
        }

        scanJobRepository.save(job);
        fileRepository.save(file);
    }
}