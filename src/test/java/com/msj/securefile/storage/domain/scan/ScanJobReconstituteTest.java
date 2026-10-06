package com.msj.securefile.storage.domain.scan;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.event.ScanJobCompleted;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.exception.ScanJobNotDueException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanJobReconstituteTest {

    private static final Instant CREATED = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant UPDATED = CREATED.plusSeconds(60);
    private static final Instant LEASE_EXPIRES = UPDATED.plusSeconds(30);
    private static final Instant NEXT_ATTEMPT = CREATED.plusSeconds(120);
    private static final ScanJobId ID = ScanJobId.of(99L);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final String ERROR = "clamd timed out";

    private static ScanJob storedLeasedJob() {
        return ScanJob.reconstitute(new ScanJobSnapshot(ID, FILE_ID, ScanQueue.LARGE, ScanJobState.LEASED, 1, CREATED,
                WORKER, LEASE_EXPIRES, ERROR, CREATED, UPDATED, 4L));
    }

    private static ScanJob storedPendingJob() {
        return ScanJob.reconstitute(new ScanJobSnapshot(ID, FILE_ID, ScanQueue.SMALL, ScanJobState.PENDING, 0,
                NEXT_ATTEMPT, null, null, null, CREATED, CREATED, 1L));
    }

    @Test
    void reconstitute_rebuildsTheStoredState() {
        ScanJob job = storedLeasedJob();

        assertThat(job.id()).isEqualTo(ID);
        assertThat(job.getFileId()).isEqualTo(FILE_ID);
        assertThat(job.getQueue()).isEqualTo(ScanQueue.LARGE);
        assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(CREATED);
        assertThat(job.getLeaseOwner()).contains(WORKER);
        assertThat(job.getLeaseExpiresAt()).contains(LEASE_EXPIRES);
        assertThat(job.getLastError()).contains(ERROR);
        assertThat(job.getCreatedAt()).isEqualTo(CREATED);
        assertThat(job.getUpdatedAt()).isEqualTo(UPDATED);
        assertThat(job.version()).isEqualTo(4L);
    }

    @Test
    void reconstitute_recordsNoEventBecauseNothingHappenedToTheJob() {
        assertThat(storedLeasedJob().pullDomainEvents()).isEmpty();
    }

    @Test
    void reconstitute_keepsTheAbsentValuesAbsent() {
        ScanJob job = storedPendingJob();

        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.getLeaseExpiresAt()).isEmpty();
        assertThat(job.getLastError()).isEmpty();
    }

    @Test
    void reconstitute_givesALiveLeasedJobThatItsOwnerCanComplete() {
        ScanJob job = storedLeasedJob();
        Instant later = UPDATED.plusSeconds(10);

        job.complete(WORKER, later);

        assertThat(job.getState()).isEqualTo(ScanJobState.DONE);
        assertThat(job.pullDomainEvents()).containsExactly(new ScanJobCompleted(ID, FILE_ID, WORKER, later));
        // The version is the loaded one until the adapter adds the number of events.
        assertThat(job.version()).isEqualTo(4L);
    }

    @Test
    void reconstitute_restoresTheLeaseOwnerSoAnotherWorkerIsShutOut() {
        ScanJob job = storedLeasedJob();

        WorkerId anotherWorker = WorkerId.of("worker-2");
        Instant later = UPDATED.plusSeconds(10);

        assertThatThrownBy(() -> job.complete(anotherWorker, later))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void reconstitute_restoresTheNextAttemptDateSoAJobIsNotClaimedBeforeItIsDue() {
        ScanJob job = storedPendingJob();

        Instant tooEarly = NEXT_ATTEMPT.minusSeconds(1);
        Duration lease = Duration.ofSeconds(30);

        assertThatThrownBy(() -> job.claim(WORKER, tooEarly, lease))
                .isInstanceOf(ScanJobNotDueException.class);
    }
}