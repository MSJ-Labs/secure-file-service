package com.msj.securefile.storage.domain.scan;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.exception.InvalidScanJobTransitionException;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.exception.LeaseNotExpiredException;
import com.msj.securefile.storage.domain.scan.exception.ScanJobNotDueException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanJobTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final ScanJobId ID = ScanJobId.of(1L);
    private static final FileId FILE = FileId.of(42L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final WorkerId OTHER_WORKER = WorkerId.of("worker-2");
    private static final ScanRetryPolicy POLICY = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));
    private static final String ERROR = "clamd read timeout";

    private static ScanJob created() {
        return ScanJob.create(ID, FILE, ScanQueue.SMALL, NOW);
    }

    private static ScanJob leased() {
        ScanJob job = created();
        job.claim(WORKER, NOW, LEASE);
        return job;
    }

    @Test
    void create_startsPendingAndDueImmediately() {
        ScanJob job = created();

        assertThat(job.id()).isEqualTo(ID);
        assertThat(job.getFileId()).isEqualTo(FILE);
        assertThat(job.getQueue()).isEqualTo(ScanQueue.SMALL);
        assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.getLeaseExpiresAt()).isEmpty();
        assertThat(job.getCreatedAt()).isEqualTo(NOW);
        assertThat(job.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void create_rejectsAMissingFileOrQueue() {
        assertThatThrownBy(() -> ScanJob.create(ID, null, ScanQueue.SMALL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScanJob.create(ID, FILE, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claim_leasesTheJobToTheWorkerForTheLeaseDuration() {
        ScanJob job = created();

        job.claim(WORKER, NOW, LEASE);

        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(job.getLeaseOwner()).contains(WORKER);
        assertThat(job.getLeaseExpiresAt()).contains(NOW.plus(LEASE));
        assertThat(job.getUpdatedAt()).isEqualTo(NOW);
    }

    // The claim is a compare-and-set on the state: the second worker loses.
    @Test
    void claim_isRefusedWhenTheJobIsAlreadyLeased() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.claim(OTHER_WORKER, NOW, LEASE))
                .isInstanceOf(InvalidScanJobTransitionException.class);
        assertThat(job.getLeaseOwner()).contains(WORKER);
    }

    @Test
    void claim_rejectsAMissingWorkerOrANonPositiveLease() {
        ScanJob job = created();

        assertThatThrownBy(() -> job.claim(null, NOW, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.claim(WORKER, NOW, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.claim(WORKER, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);
    }

    // The heartbeat: the lease is pushed forward from the moment of the renewal, not from the original claim.
    @Test
    void renew_extendsTheLeaseFromTheRenewalTime() {
        ScanJob job = leased();
        Instant later = NOW.plusSeconds(10);

        job.renew(WORKER, later, LEASE);

        assertThat(job.getLeaseExpiresAt()).contains(later.plus(LEASE));
        assertThat(job.getLeaseOwner()).contains(WORKER);
        assertThat(job.getUpdatedAt()).isEqualTo(later);
    }

    // A worker that is no longer the owner must stop: its heartbeat and its completion are both refused.
    @Test
    void renew_isRefusedForAnotherWorkerOrAJobThatIsNotLeased() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.renew(OTHER_WORKER, NOW, LEASE)).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> created().renew(WORKER, NOW, LEASE)).isInstanceOf(LeaseLostException.class);
        assertThat(job.getLeaseExpiresAt()).contains(NOW.plus(LEASE));
    }

    @Test
    void complete_marksTheJobDoneAndReleasesTheLease() {
        ScanJob job = leased();
        Instant later = NOW.plusSeconds(20);

        job.complete(WORKER, later);

        assertThat(job.getState()).isEqualTo(ScanJobState.DONE);
        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.getLeaseExpiresAt()).isEmpty();
        assertThat(job.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void complete_isRefusedForAnotherWorkerOrOnceDone() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.complete(OTHER_WORKER, NOW)).isInstanceOf(LeaseLostException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);

        job.complete(WORKER, NOW);

        assertThatThrownBy(() -> job.complete(WORKER, NOW)).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void create_hasNoErrorYet() {
        assertThat(created().getLastError()).isEmpty();
    }

    @Test
    void fail_schedulesARetryAfterTheBackoff() {
        ScanJob job = leased();
        Instant failedAt = NOW.plusSeconds(5);

        ScanFailureOutcome outcome = job.fail(WORKER, ERROR, failedAt, POLICY);

        assertThat(outcome).isEqualTo(ScanFailureOutcome.RETRY_SCHEDULED);
        assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(failedAt.plusSeconds(10));
        assertThat(job.getLastError()).contains(ERROR);
        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.getLeaseExpiresAt()).isEmpty();
        assertThat(job.getUpdatedAt()).isEqualTo(failedAt);
    }

    @Test
    void fail_backsOffLongerAtEachFailure() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        Instant secondClaim = NOW.plusSeconds(10);
        job.claim(WORKER, secondClaim, LEASE);

        job.fail(WORKER, ERROR, secondClaim, POLICY);

        assertThat(job.getAttempts()).isEqualTo(2);
        assertThat(job.getNextAttemptAt()).isEqualTo(secondClaim.plusSeconds(20));
    }

    // After the last allowed attempt the job is over: the handler then fails the file instead of requeuing it.
    @Test
    void fail_givesUpOnceTheAttemptsAreExhausted() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        job.claim(WORKER, NOW.plusSeconds(10), LEASE);
        job.fail(WORKER, ERROR, NOW.plusSeconds(10), POLICY);
        job.claim(WORKER, NOW.plusSeconds(30), LEASE);

        ScanFailureOutcome outcome = job.fail(WORKER, "still failing", NOW.plusSeconds(30), POLICY);

        assertThat(outcome).isEqualTo(ScanFailureOutcome.EXHAUSTED);
        assertThat(job.getState()).isEqualTo(ScanJobState.DONE);
        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(job.getLastError()).contains("still failing");
        assertThat(job.getLeaseOwner()).isEmpty();
    }

    @Test
    void fail_isRefusedForAWorkerThatDoesNotOwnTheJob() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.fail(OTHER_WORKER, ERROR, NOW, POLICY)).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> created().fail(WORKER, ERROR, NOW, POLICY)).isInstanceOf(LeaseLostException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(job.getAttempts()).isZero();
    }

    @Test
    void fail_requiresAnErrorMessageAndAPolicy() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.fail(WORKER, null, NOW, POLICY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.fail(WORKER, "  ", NOW, POLICY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.fail(WORKER, ERROR, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
    }

    @Test
    void claim_isRefusedBeforeTheNextAttemptIsDue() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        Instant due = job.getNextAttemptAt();

        assertThatThrownBy(() -> job.claim(OTHER_WORKER, due.minusSeconds(1), LEASE))
                .isInstanceOf(ScanJobNotDueException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);

        job.claim(OTHER_WORKER, due, LEASE);

        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(job.getLeaseOwner()).contains(OTHER_WORKER);
    }

    // The file is not at fault (ClamAV down, worker shutting down): no attempt is consumed.
    @Test
    void release_returnsTheJobToTheQueueWithoutConsumingAnAttempt() {
        ScanJob job = leased();
        Instant releasedAt = NOW.plusSeconds(5);

        job.release(WORKER, releasedAt, Duration.ofSeconds(30));

        assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getNextAttemptAt()).isEqualTo(releasedAt.plusSeconds(30));
        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.getLeaseExpiresAt()).isEmpty();
        assertThat(job.getLastError()).isEmpty();
        assertThat(job.getUpdatedAt()).isEqualTo(releasedAt);
    }

    @Test
    void release_keepsTheAttemptsAndTheErrorOfEarlierFailures() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        job.claim(WORKER, job.getNextAttemptAt(), LEASE);

        job.release(WORKER, job.getNextAttemptAt(), Duration.ofSeconds(1));

        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains(ERROR);
    }

    // A worker that shuts down hands its job back to be picked up at once.
    @Test
    void release_withNoDelayMakesTheJobDueImmediately() {
        ScanJob job = leased();
        Instant releasedAt = NOW.plusSeconds(5);

        job.release(WORKER, releasedAt, Duration.ZERO);
        job.claim(OTHER_WORKER, releasedAt, LEASE);

        assertThat(job.getLeaseOwner()).contains(OTHER_WORKER);
    }

    @Test
    void release_rejectsAMissingOrNegativeDelay() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.release(WORKER, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.release(WORKER, NOW, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
    }

    @Test
    void release_isRefusedForAWorkerThatDoesNotOwnTheJob() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.release(OTHER_WORKER, NOW, Duration.ZERO))
                .isInstanceOf(LeaseLostException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
    }

    // The reaper: the worker died, so the attempt counts (a file that kills its worker must not loop forever).
    @Test
    void reclaimExpired_consumesAnAttemptAndSchedulesARetry() {
        ScanJob job = leased();
        Instant expiry = NOW.plus(LEASE);

        ScanFailureOutcome outcome = job.reclaimExpired(expiry, POLICY);

        assertThat(outcome).isEqualTo(ScanFailureOutcome.RETRY_SCHEDULED);
        assertThat(job.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(expiry.plusSeconds(10));
        assertThat(job.getLastError()).isPresent();
        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.getLeaseExpiresAt()).isEmpty();
        assertThat(job.getUpdatedAt()).isEqualTo(expiry);
    }

    @Test
    void reclaimExpired_givesUpOnceTheAttemptsAreExhausted() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        job.claim(WORKER, NOW.plusSeconds(10), LEASE);
        job.fail(WORKER, ERROR, NOW.plusSeconds(10), POLICY);
        job.claim(WORKER, NOW.plusSeconds(30), LEASE);

        ScanFailureOutcome outcome = job.reclaimExpired(NOW.plusSeconds(60), POLICY);

        assertThat(outcome).isEqualTo(ScanFailureOutcome.EXHAUSTED);
        assertThat(job.getState()).isEqualTo(ScanJobState.DONE);
        assertThat(job.getAttempts()).isEqualTo(3);
    }

    @Test
    void reclaimExpired_isRefusedWhileTheLeaseIsStillValid() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.reclaimExpired(NOW.plus(LEASE).minusSeconds(1), POLICY))
                .isInstanceOf(LeaseNotExpiredException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(job.getLeaseOwner()).contains(WORKER);
        assertThat(job.getAttempts()).isZero();
    }

    @Test
    void reclaimExpired_isRefusedForAJobThatIsNotLeased() {
        assertThatThrownBy(() -> created().reclaimExpired(NOW, POLICY))
                .isInstanceOf(InvalidScanJobTransitionException.class);
    }

    @Test
    void reclaimExpired_requiresAPolicy() {
        ScanJob job = leased();

        assertThatThrownBy(() -> job.reclaimExpired(NOW.plus(LEASE), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
    }

    // The slow worker comes back after the reaper took the job: it must learn it lost the lease, not overwrite.
    @Test
    void aWorkerWhoseLeaseWasReclaimedCanNoLongerRenewOrComplete() {
        ScanJob job = leased();
        Instant expiry = NOW.plus(LEASE);
        job.reclaimExpired(expiry, POLICY);
        job.claim(OTHER_WORKER, job.getNextAttemptAt(), LEASE);

        assertThatThrownBy(() -> job.renew(WORKER, expiry, LEASE)).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> job.complete(WORKER, expiry)).isInstanceOf(LeaseLostException.class);
        assertThat(job.getLeaseOwner()).contains(OTHER_WORKER);
    }
}