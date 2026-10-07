package com.msj.securefile.storage.infrastructure.worker;

import com.msj.securefile.storage.application.command.claimscan.ClaimScanCommand;
import com.msj.securefile.storage.application.command.claimscan.ClaimScanCommandHandler;
import com.msj.securefile.storage.application.command.processscan.ProcessScanCommand;
import com.msj.securefile.storage.application.command.processscan.ProcessScanCommandHandler;
import com.msj.securefile.storage.application.result.ClaimedScan;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * One poll of one queue: claims as many jobs as the worker has free slots and runs each scan on the executor. The
 * slots are permits released when a scan ends, whatever its outcome, so a sick job never shrinks the capacity.
 * <p>
 * It never throws: a failing claim (database down) is a miss that backs off, and the loop that calls it keeps going.
 */
@Slf4j
public class ScanQueueWorker {

    private final ScanQueue queue;
    private final WorkerId worker;
    private final Duration lease;
    private final Duration unavailableDelay;
    private final PollingPolicy pollingPolicy;
    private final ClaimScanCommandHandler claimScan;
    private final ProcessScanCommandHandler processScan;
    private final Executor executor;
    private final Semaphore slots;

    public ScanQueueWorker(ScanQueue queue, WorkerId worker, Duration lease, int slots, Duration unavailableDelay,
                           PollingPolicy pollingPolicy, ClaimScanCommandHandler claimScan,
                           ProcessScanCommandHandler processScan, Executor executor) {
        if (slots <= 0) throw new IllegalArgumentException("A worker needs at least one slot");

        this.queue = queue;
        this.worker = worker;
        this.lease = lease;
        this.unavailableDelay = unavailableDelay;
        this.pollingPolicy = pollingPolicy;
        this.claimScan = claimScan;
        this.processScan = processScan;
        this.executor = executor;
        this.slots = new Semaphore(slots);
    }

    public int freeSlots() {
        return slots.availablePermits();
    }

    /**
     * @return how long to wait before the next poll
     */
    public Duration poll(Duration previousDelay) {
        // Taking every free permit at once: only this loop acquires, so nothing can be stolen between the two steps.
        int free = slots.drainPermits();
        if (free == 0) {
            return pollingPolicy.afterMiss(previousDelay);
        }

        List<ClaimedScan> claimed;
        try {
            claimed = claimScan.handle(new ClaimScanCommand(queue, worker, lease, free));
        } catch (RuntimeException e) {
            log.error("Could not claim scans from the {} queue", queue, e);
            slots.release(free);
            return pollingPolicy.afterMiss(previousDelay);
        }

        slots.release(free - claimed.size());
        claimed.forEach(this::start);
        return claimed.isEmpty() ? pollingPolicy.afterMiss(previousDelay) : pollingPolicy.afterHit();
    }

    private void start(ClaimedScan scan) {
        try {
            executor.execute(() -> run(scan));
        } catch (RuntimeException e) {
            // Not started: the lease expires and the reclaim gives the job back.
            log.error("Could not start the scan of job {}", scan.jobId(), e);
            slots.release();
        }
    }

    private void run(ClaimedScan scan) {
        try {
            processScan.handle(new ProcessScanCommand(scan.jobId(), scan.fileId(), worker, unavailableDelay));
        } catch (RuntimeException e) {
            // Typically a lost lease: another worker owns the job now, nothing is left to do here.
            log.warn("The scan of job {} ended with an error", scan.jobId(), e);
        } finally {
            slots.release();
        }
    }
}
