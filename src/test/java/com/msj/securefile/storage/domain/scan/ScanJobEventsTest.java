package com.msj.securefile.storage.domain.scan;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.event.ScanAbandoned;
import com.msj.securefile.storage.domain.scan.event.ScanClaimed;
import com.msj.securefile.storage.domain.scan.event.ScanJobCompleted;
import com.msj.securefile.storage.domain.scan.event.ScanJobCreated;
import com.msj.securefile.storage.domain.scan.event.ScanJobEvent;
import com.msj.securefile.storage.domain.scan.event.ScanReleased;
import com.msj.securefile.storage.domain.scan.event.ScanRetryScheduled;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every successful transition of the job records one event, so the attempts of a scan are kept even though the
 * job row only holds the current state. The heartbeat is not a transition: it would write one event every few
 * seconds for nothing.
 */
class ScanJobEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final ScanJobId ID = ScanJobId.of(1L);
    private static final FileId FILE = FileId.of(42L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final WorkerId OTHER_WORKER = WorkerId.of("worker-2");
    private static final ScanRetryPolicy POLICY = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));
    private static final String ERROR = "clamd read timeout";

    // Setup events are dropped so each test only sees the event of the transition it exercises.
    private static ScanJob pending() {
        ScanJob job = ScanJob.create(ID, FILE, ScanQueue.SMALL, NOW);
        job.pullDomainEvents();
        return job;
    }

    private static ScanJob leased() {
        ScanJob job = pending();
        job.claim(WORKER, NOW, LEASE);
        job.pullDomainEvents();
        return job;
    }

    @Test
    void create_recordsScanJobCreated() {
        ScanJob job = ScanJob.create(ID, FILE, ScanQueue.LARGE, NOW);

        assertThat(job.pullDomainEvents()).containsExactly(new ScanJobCreated(ID, FILE, ScanQueue.LARGE, NOW));
    }

    @Test
    void claim_recordsScanClaimedWithTheNumberOfTheAttempt() {
        ScanJob job = pending();

        job.claim(WORKER, NOW, LEASE);

        assertThat(job.pullDomainEvents())
                .containsExactly(new ScanClaimed(ID, FILE, WORKER, 1, NOW.plus(LEASE), NOW));
    }

    // The second claim follows one failed attempt: it is the second attempt.
    @Test
    void claim_afterAFailureIsTheNextAttempt() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        job.pullDomainEvents();
        Instant due = NOW.plusSeconds(10);

        job.claim(WORKER, due, LEASE);

        assertThat(job.pullDomainEvents()).containsExactly(new ScanClaimed(ID, FILE, WORKER, 2, due.plus(LEASE), due));
    }

    @Test
    void renew_recordsNothing() {
        ScanJob job = leased();

        job.renew(WORKER, NOW.plusSeconds(10), LEASE);

        assertThat(job.pullDomainEvents()).isEmpty();
    }

    @Test
    void complete_recordsScanJobCompleted() {
        ScanJob job = leased();
        Instant done = NOW.plusSeconds(20);

        job.complete(WORKER, done);

        assertThat(job.pullDomainEvents()).containsExactly(new ScanJobCompleted(ID, FILE, WORKER, done));
    }

    @Test
    void fail_recordsScanRetryScheduledWithTheNextAttemptDate() {
        ScanJob job = leased();
        Instant failedAt = NOW.plusSeconds(5);

        job.fail(WORKER, ERROR, failedAt, POLICY);

        assertThat(job.pullDomainEvents()).containsExactly(new ScanRetryScheduled(
                ID, FILE, WORKER, 1, ScanFailureCause.REPORTED, ERROR, failedAt.plusSeconds(10), failedAt));
    }

    @Test
    void fail_recordsScanAbandonedOnceTheAttemptsAreExhausted() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        job.claim(WORKER, NOW.plusSeconds(10), LEASE);
        job.fail(WORKER, ERROR, NOW.plusSeconds(10), POLICY);
        job.claim(WORKER, NOW.plusSeconds(30), LEASE);
        job.pullDomainEvents();

        job.fail(WORKER, "still failing", NOW.plusSeconds(30), POLICY);

        assertThat(job.pullDomainEvents()).containsExactly(new ScanAbandoned(
                ID, FILE, WORKER, 3, ScanFailureCause.REPORTED, "still failing", NOW.plusSeconds(30)));
    }

    // Releasing is not a failure: it has its own event and no attempt number.
    @Test
    void release_recordsScanReleasedWithTheNextAttemptDate() {
        ScanJob job = leased();
        Instant releasedAt = NOW.plusSeconds(5);

        job.release(WORKER, releasedAt, Duration.ofSeconds(30));

        assertThat(job.pullDomainEvents())
                .containsExactly(new ScanReleased(ID, FILE, WORKER, releasedAt.plusSeconds(30), releasedAt));
    }

    // The worker named in the event is the one that lost the lease, not the reaper.
    @Test
    void reclaimExpired_recordsAScheduledRetryCausedByTheExpiredLease() {
        ScanJob job = leased();
        Instant expiry = NOW.plus(LEASE);

        job.reclaimExpired(expiry, POLICY);

        assertThat(job.pullDomainEvents()).singleElement().isInstanceOfSatisfying(ScanRetryScheduled.class, event -> {
            assertThat(event.worker()).isEqualTo(WORKER);
            assertThat(event.attemptNumber()).isEqualTo(1);
            assertThat(event.cause()).isEqualTo(ScanFailureCause.LEASE_EXPIRED);
            assertThat(event.nextAttemptAt()).isEqualTo(expiry.plusSeconds(10));
            assertThat(event.occurredOn()).isEqualTo(expiry);
        });
    }

    @Test
    void reclaimExpired_recordsAnAbandonmentWhenTheAttemptsAreExhausted() {
        ScanJob job = leased();
        job.fail(WORKER, ERROR, NOW, POLICY);
        job.claim(WORKER, NOW.plusSeconds(10), LEASE);
        job.fail(WORKER, ERROR, NOW.plusSeconds(10), POLICY);
        job.claim(WORKER, NOW.plusSeconds(30), LEASE);
        job.pullDomainEvents();

        job.reclaimExpired(NOW.plusSeconds(60), POLICY);

        assertThat(job.pullDomainEvents()).singleElement().isInstanceOfSatisfying(ScanAbandoned.class, event -> {
            assertThat(event.worker()).isEqualTo(WORKER);
            assertThat(event.attemptNumber()).isEqualTo(3);
            assertThat(event.cause()).isEqualTo(ScanFailureCause.LEASE_EXPIRED);
        });
    }

    @Test
    void aRefusedTransitionRecordsNothing() {
        ScanJob leased = leased();
        ScanJob pending = pending();

        assertThatThrownBy(() -> leased.claim(OTHER_WORKER, NOW, LEASE)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> leased.renew(OTHER_WORKER, NOW, LEASE)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> leased.complete(OTHER_WORKER, NOW)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> leased.fail(OTHER_WORKER, ERROR, NOW, POLICY)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> leased.fail(WORKER, " ", NOW, POLICY)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> leased.release(OTHER_WORKER, NOW, Duration.ZERO)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> leased.reclaimExpired(NOW, POLICY)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> pending.reclaimExpired(NOW, POLICY)).isInstanceOf(RuntimeException.class);

        assertThat(leased.pullDomainEvents()).isEmpty();
        assertThat(pending.pullDomainEvents()).isEmpty();
    }

    @Test
    void aReleaseThenACompletionAreRecordedInOrder() {
        ScanJob job = ScanJob.create(ID, FILE, ScanQueue.SMALL, NOW);
        job.claim(WORKER, NOW, LEASE);
        job.release(WORKER, NOW.plusSeconds(5), Duration.ZERO);
        job.claim(OTHER_WORKER, NOW.plusSeconds(5), LEASE);
        job.complete(OTHER_WORKER, NOW.plusSeconds(20));

        List<DomainEvent> events = job.pullDomainEvents();

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("ScanJobCreated", "ScanClaimed", "ScanReleased", "ScanClaimed", "ScanJobCompleted");
        // The release did not consume an attempt: both claims are the first attempt.
        assertThat(events).filteredOn(ScanClaimed.class::isInstance).extracting(event -> ((ScanClaimed) event).attemptNumber())
                .containsExactly(1, 1);
    }

    // Every event says which job and which file it is about, so the history of a file can be read in one query.
    @Test
    void everyEventIdentifiesItsJobAndItsFile() {
        ScanJob job = ScanJob.create(ID, FILE, ScanQueue.SMALL, NOW);
        job.claim(WORKER, NOW, LEASE);
        job.fail(WORKER, ERROR, NOW, POLICY);

        assertThat(job.pullDomainEvents()).allSatisfy(event -> {
            assertThat(((ScanJobEvent) event).jobId()).isEqualTo(ID);
            assertThat(((ScanJobEvent) event).fileId()).isEqualTo(FILE);
        });
    }
}