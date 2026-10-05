package com.msj.securefile.storage.application.command.reclaimleases;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanFailureOutcome;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        if (expired.isEmpty()) {
            return 0;
        }
        Map<FileId, SecureFile> files = fileRepository
                .findAllByIdForScan(expired.stream().map(ScanJob::getFileId).toList())
                .stream()
                .collect(Collectors.toMap(SecureFile::id, Function.identity()));

        List<SecureFile> changed = new ArrayList<>();
        for (ScanJob job : expired) {
            // The job is always taken back, so it leaves its dead lease and counts the attempt it consumed.
            ScanFailureOutcome outcome = job.reclaimExpired(now, scanRetryPolicy);

            SecureFile file = files.get(job.getFileId());
            // A file that is not being scanned although its job held a lease is inconsistent data. It must not fail
            // the rest of the batch: its file is left alone, nobody knows what state it should be in.
            if (file != null && file.getStatus() == FileStatus.SCANNING) {
                switch (outcome) {
                    case RETRY_SCHEDULED -> file.requeueScan(now);
                    case EXHAUSTED -> file.failScan(now);
                }
                changed.add(file);
            }
        }

        // The dead worker cannot act: the reaper that takes the job back is the system itself.
        Actor actor = new Actor.System();
        scanJobRepository.saveAll(expired, actor);
        if (!changed.isEmpty()) {
            fileRepository.saveAll(changed, actor);
        }
        return expired.size();
    }
}