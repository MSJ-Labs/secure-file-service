package com.msj.securefile.storage.domain.scan;

import com.msj.securefile.shared.domain.AggregateRoot;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.event.*;
import com.msj.securefile.storage.domain.scan.exception.InvalidScanJobTransitionException;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.exception.LeaseNotExpiredException;
import com.msj.securefile.storage.domain.scan.exception.ScanJobNotDueException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The work item of one scan, separate from the file it refers to. A worker holds it through a short lease that a
 * heartbeat keeps extending, so a dead worker is detected within the lease duration whatever the file size.
 */
@Getter
public class ScanJob extends AggregateRoot<ScanJobId> {

    private static final String LEASE_EXPIRED_ERROR = "The lease expired before the worker finished.";

    private final FileId fileId;
    private final ScanQueue queue;
    private ScanJobState state;
    private int attempts;
    private Instant nextAttemptAt;
    private WorkerId leaseOwner;
    private Instant leaseExpiresAt;
    // Why the last attempt failed. Kept after the job is over: it is the trace of what went wrong.
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;

    private ScanJob(ScanJobId id, FileId fileId, ScanQueue queue, ScanJobState state, int attempts,
                    Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {
        super(id);
        this.fileId = fileId;
        this.queue = queue;
        this.state = state;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ScanJob create(ScanJobId id, FileId fileId, ScanQueue queue, Instant now) {
        if (fileId == null) throw new IllegalArgumentException("File is required");
        if (queue == null) throw new IllegalArgumentException("Queue is required");

        ScanJob job = new ScanJob(id, fileId, queue, ScanJobState.PENDING, 0, now, now, now);
        job.registerEvent(new ScanJobCreated(id, fileId, queue, now));
        return job;
    }

    /**
     * Reserves the job. Only a PENDING job can be reserved, so two workers cannot take the same one: the loser
     * sees the transition refused.
     */
    public void claim(WorkerId worker, Instant now, Duration lease) {
        if (worker == null) throw new IllegalArgumentException("Worker is required");
        requirePositive(lease);
        if (state != ScanJobState.PENDING) throw new InvalidScanJobTransitionException(state, ScanJobState.LEASED);
        // The claim query already filters on the date; this keeps the rule in the domain too.
        if (now.isBefore(nextAttemptAt)) throw new ScanJobNotDueException();

        this.state = ScanJobState.LEASED;
        this.leaseOwner = worker;
        this.leaseExpiresAt = now.plus(lease);
        this.updatedAt = now;
        // Failed attempts so far plus this one: a release did not consume an attempt.
        registerEvent(new ScanClaimed(id(), fileId, worker, attempts + 1, leaseExpiresAt, now));
    }

    /**
     * Heartbeat: pushes the expiry forward from now. Only the owner can renew; anyone else has lost the job.
     */
    public void renew(WorkerId worker, Instant now, Duration lease) {
        requirePositive(lease);
        requireOwner(worker);

        this.leaseExpiresAt = now.plus(lease);
        this.updatedAt = now;
    }

    // Expiry is not checked on purpose: if nobody took the job over, the owner may still finish it, which is what
    // the compare-and-set on the owner does in SQL.
    public void complete(WorkerId worker, Instant now) {
        requireOwner(worker);

        this.state = ScanJobState.DONE;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        registerEvent(new ScanJobCompleted(id(), fileId, worker, now));
    }

    /**
     * The scan itself failed (I/O error, timeout, protocol error). The failure counts as an attempt: the job is
     * rescheduled after a backoff, or given up on when the policy says so. The caller applies the outcome to the file.
     */
    public ScanFailureOutcome fail(WorkerId worker, String error, Instant now, ScanRetryPolicy policy) {
        if (error == null || error.isBlank()) throw new IllegalArgumentException("The error message is required");
        if (policy == null) throw new IllegalArgumentException("The retry policy is required");
        requireOwner(worker);

        return recordFailedAttempt(worker, error.trim(), ScanFailureCause.REPORTED, now, policy);
    }

    /**
     * Reaper entry point: the worker holding the lease stopped heartbeating. The attempt counts, because a file
     * that kills its worker must not be retried forever. The former owner is shut out: it can no longer renew or
     * complete, since it does not hold the lease anymore.
     */
    public ScanFailureOutcome reclaimExpired(Instant now, ScanRetryPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("The retry policy is required");
        if (state != ScanJobState.LEASED) throw new InvalidScanJobTransitionException(state, ScanJobState.PENDING);
        if (now.isBefore(leaseExpiresAt)) throw new LeaseNotExpiredException();

        return recordFailedAttempt(leaseOwner, LEASE_EXPIRED_ERROR, ScanFailureCause.LEASE_EXPIRED, now, policy);
    }

    // Shared by a reported failure and an expired lease: both consume an attempt and free the job. The worker is the
    // one that held the lease, kept for the history since the lease is cleared here.
    private ScanFailureOutcome recordFailedAttempt(WorkerId worker, String error, ScanFailureCause cause,
                                                   Instant now, ScanRetryPolicy policy) {
        this.attempts++;
        this.lastError = error;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        if (policy.isExhausted(attempts)) {
            this.state = ScanJobState.DONE;
            registerEvent(new ScanAbandoned(id(), fileId, worker, attempts, cause, error, now));
            return ScanFailureOutcome.EXHAUSTED;
        }
        this.state = ScanJobState.PENDING;
        this.nextAttemptAt = now.plus(policy.backoff(attempts));
        registerEvent(new ScanRetryScheduled(id(), fileId, worker, attempts, cause, error, nextAttemptAt, now));
        return ScanFailureOutcome.RETRY_SCHEDULED;
    }

    /**
     * Hands the job back without any fault of the file (ClamAV unreachable, worker shutting down): no attempt is
     * consumed, so an outage can never push a healthy file toward the limit.
     */
    public void release(WorkerId worker, Instant now, Duration delay) {
        if (delay == null || delay.isNegative()) throw new IllegalArgumentException("The delay cannot be negative");
        requireOwner(worker);

        this.state = ScanJobState.PENDING;
        this.nextAttemptAt = now.plus(delay);
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        registerEvent(new ScanReleased(id(), fileId, worker, nextAttemptAt, now));
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<WorkerId> getLeaseOwner() {
        return Optional.ofNullable(leaseOwner);
    }

    public Optional<Instant> getLeaseExpiresAt() {
        return Optional.ofNullable(leaseExpiresAt);
    }

    private void requireOwner(WorkerId worker) {
        if (state != ScanJobState.LEASED || !leaseOwner.equals(worker)) throw new LeaseLostException();
    }

    private static void requirePositive(Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("The lease duration must be positive");
        }
    }
}