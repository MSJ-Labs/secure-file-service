package com.msj.securefile.storage.application.command.claimscan;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.application.result.ClaimedScan;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
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
 * A worker takes the next due jobs of its queue, up to its free slots, in one short transaction: the jobs and their
 * files change state together and are written in batches. The scan itself runs outside of it, protected by the lease.
 */
@Service
@RequiredArgsConstructor
public class ClaimScanCommandHandler {

    private static final String FILE_NOT_WAITING = "The file is not waiting for a scan.";

    private final ScanJobRepository scanJobRepository;
    private final FileRepository fileRepository;
    private final ScanRetryPolicy scanRetryPolicy;
    private final Clock clock;

    @Transactional
    public List<ClaimedScan> handle(ClaimScanCommand command) {
        Instant now = clock.instant();

        List<ScanJob> jobs = scanJobRepository.findNextDue(command.queue(), now, command.limit());
        if (jobs.isEmpty()) {
            return List.of();
        }
        Map<FileId, SecureFile> files = fileRepository
                .findAllByIdForScan(jobs.stream().map(ScanJob::getFileId).toList())
                .stream()
                .collect(Collectors.toMap(SecureFile::id, Function.identity()));

        List<SecureFile> scanning = new ArrayList<>();
        List<ClaimedScan> claimed = new ArrayList<>();
        for (ScanJob job : jobs) {
            job.claim(command.worker(), now, command.lease());
            SecureFile file = files.get(job.getFileId());
            if (file != null && file.getStatus() == FileStatus.PENDING) {
                file.startScan(now);
                scanning.add(file);
                claimed.add(new ClaimedScan(job.id(), job.getFileId(), now.plus(command.lease())));
            } else {
                // A job whose file is not waiting is inconsistent data. It must not fail the rest of the batch, nor
                // stay at the head of the queue: it counts a failed attempt and waits for its backoff. Its file is
                // left alone, nobody knows what state it should be in.
                job.fail(command.worker(), FILE_NOT_WAITING, now, scanRetryPolicy);
            }
        }

        Actor actor = new Actor.Worker(command.worker());
        scanJobRepository.saveAll(jobs, actor);
        if (!scanning.isEmpty()) {
            fileRepository.saveAll(scanning, actor);
        }
        return claimed;
    }
}
