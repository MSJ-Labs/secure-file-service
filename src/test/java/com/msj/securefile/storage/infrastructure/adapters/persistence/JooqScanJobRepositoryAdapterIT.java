package com.msj.securefile.storage.infrastructure.adapters.persistence;

import com.msj.securefile.shared.infrastructure.persistence.jooq.storage.tables.records.FileEventRecord;
import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.ConcurrentUpdateException;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanJobState;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import com.msj.securefile.storage.infrastructure.adapters.persistence.audit.AuditEventMapper;
import com.msj.securefile.storage.infrastructure.adapters.persistence.audit.FileEventWriter;
import com.msj.securefile.support.PostgresTestDatabase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.storage.Tables.FILE_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JooqScanJobRepositoryAdapterIT {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final Actor ACTOR = new Actor.Worker(WORKER);
    private static final ScanRetryPolicy RETRY = new ScanRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(5));

    private final DSLContext dsl = PostgresTestDatabase.dsl();
    private final FileEventWriter eventWriter =
            new FileEventWriter(dsl, new AuditEventMapper(), JsonMapper.builder().build());
    private final JooqFileRepositoryAdapter files = new JooqFileRepositoryAdapter(dsl, eventWriter);
    private final JooqScanJobRepositoryAdapter repository = new JooqScanJobRepositoryAdapter(dsl, eventWriter);

    @BeforeEach
    void emptyTheStorageTables() {
        PostgresTestDatabase.deleteAllStorageData();
    }

    // A job belongs to a file (foreign key, one job per file): the file row has to exist first.
    private ScanJob newJob(long id, ScanQueue queue, Instant createdAt) {
        files.save(SecureFile.initiate(FileId.of(id), OwnerId.of(7L), "report.pdf", 1_024, NOW, NOW.plusSeconds(3_600)),
                new Actor.User(OwnerId.of(7L)));
        return ScanJob.create(ScanJobId.of(id), FileId.of(id), queue, createdAt);
    }

    private ScanJob saved(ScanJob job) {
        repository.save(job, ACTOR);
        return repository.findById(job.id()).orElseThrow();
    }

    private ScanJob leased(long id, Instant claimedAt) {
        ScanJob job = saved(newJob(id, ScanQueue.SMALL, NOW.minusSeconds(1_000)));
        job.claim(WORKER, claimedAt, LEASE);
        return saved(job);
    }

    private List<FileEventRecord> eventsOf(long jobId) {
        // The tests give the job and its file the same id, so the type of the aggregate has to be part of the filter.
        return dsl.selectFrom(FILE_EVENT)
                .where(FILE_EVENT.AGGREGATE_TYPE.eq("SCAN_JOB"))
                .and(FILE_EVENT.AGGREGATE_ID.eq(jobId))
                .orderBy(FILE_EVENT.VERSION)
                .fetch();
    }

    @Test
    void save_thenFind_rebuildsANewJobWithEveryField() {
        ScanJob found = saved(newJob(1L, ScanQueue.LARGE, NOW));

        assertThat(found.id()).isEqualTo(ScanJobId.of(1L));
        assertThat(found.getFileId()).isEqualTo(FileId.of(1L));
        assertThat(found.getQueue()).isEqualTo(ScanQueue.LARGE);
        assertThat(found.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(found.getLeaseOwner()).isEmpty();
        assertThat(found.getLastError()).isEmpty();
        assertThat(found.getCreatedAt()).isEqualTo(NOW);
        assertThat(found.version()).isEqualTo(1L);
        assertThat(found.pullDomainEvents()).isEmpty();
    }

    @Test
    void save_writesTheEventsOfTheChangeUnderTheJobAndItsFile() {
        saved(newJob(1L, ScanQueue.SMALL, NOW));

        assertThat(eventsOf(1L)).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("ScanJobCreated");
            assertThat(row.getAggregateType()).isEqualTo("SCAN_JOB");
            assertThat(row.getFileId()).isEqualTo(1L);
            assertThat(row.getActorType()).isEqualTo("WORKER");
            assertThat(row.getActorId()).isEqualTo("worker-1");
        });
    }

    @Test
    void save_persistsAClaimAndAdvancesTheVersion() {
        ScanJob job = saved(newJob(1L, ScanQueue.SMALL, NOW));

        job.claim(WORKER, LATER, LEASE);
        ScanJob reloaded = saved(job);

        assertThat(reloaded.getState()).isEqualTo(ScanJobState.LEASED);
        assertThat(reloaded.getLeaseOwner()).contains(WORKER);
        assertThat(reloaded.getLeaseExpiresAt()).contains(LATER.plus(LEASE));
        assertThat(reloaded.version()).isEqualTo(2L);
        assertThat(eventsOf(1L)).extracting(FileEventRecord::getVersion).containsExactly(1L, 2L);
    }

    @Test
    void save_persistsAFailedAttemptAndItsRetry() {
        ScanJob job = saved(newJob(1L, ScanQueue.SMALL, NOW));
        job.claim(WORKER, LATER, LEASE);
        job.fail(WORKER, "clamd timed out", LATER.plusSeconds(5), RETRY);

        ScanJob reloaded = saved(job);

        assertThat(reloaded.getState()).isEqualTo(ScanJobState.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("clamd timed out");
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(LATER.plusSeconds(5).plus(RETRY.backoff(1)));
        assertThat(reloaded.getLeaseOwner()).isEmpty();
    }

    @Test
    void save_doesNothingWhenNothingChanged() {
        ScanJob job = saved(newJob(1L, ScanQueue.SMALL, NOW));

        repository.save(job, ACTOR);

        assertThat(repository.findById(job.id()).orElseThrow().version()).isEqualTo(1L);
        assertThat(eventsOf(1L)).hasSize(1);
    }

    @Test
    void save_refusesAStaleCopyAndKeepsTheFirstWriterState() {
        saved(newJob(1L, ScanQueue.SMALL, NOW));
        ScanJob first = repository.findById(ScanJobId.of(1L)).orElseThrow();
        ScanJob second = repository.findById(ScanJobId.of(1L)).orElseThrow();
        first.claim(WORKER, LATER, LEASE);
        repository.save(first, ACTOR);

        // Two workers loaded the same pending job: only the first claim may stand.
        second.claim(WorkerId.of("worker-2"), LATER, LEASE);

        Actor secondWorker = new Actor.Worker(WorkerId.of("worker-2"));

        assertThatThrownBy(() -> repository.save(second, secondWorker))
                .isInstanceOf(ConcurrentUpdateException.class);
        assertThat(repository.findById(ScanJobId.of(1L)).orElseThrow().getLeaseOwner()).contains(WORKER);
    }

    @Test
    void findById_isEmptyForAnUnknownJob() {
        assertThat(repository.findById(ScanJobId.of(404L))).isEmpty();
    }

    @Test
    void renewLease_extendsTheExpiryWithoutTouchingTheVersionNorTheAudit() {
        ScanJob job = leased(1L, NOW);

        job.renew(WORKER, NOW.plusSeconds(10), LEASE);
        repository.renewLease(job);

        ScanJob reloaded = repository.findById(job.id()).orElseThrow();
        assertThat(reloaded.getLeaseExpiresAt()).contains(NOW.plusSeconds(10).plus(LEASE));
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(10));
        // A heartbeat is no transition: the version stays and no event is written.
        assertThat(reloaded.version()).isEqualTo(job.version());
        assertThat(eventsOf(1L)).hasSize(2);
    }

    @Test
    void renewLease_tellsAWorkerThatLostTheJobToStop() {
        leased(1L, NOW);
        ScanJob staleHolder = repository.findById(ScanJobId.of(1L)).orElseThrow();
        ScanJob releasing = repository.findById(ScanJobId.of(1L)).orElseThrow();
        releasing.release(WORKER, NOW.plusSeconds(5), Duration.ofSeconds(5));
        repository.save(releasing, ACTOR);

        staleHolder.renew(WORKER, NOW.plusSeconds(10), LEASE);

        assertThatThrownBy(() -> repository.renewLease(staleHolder)).isInstanceOf(LeaseLostException.class);
        assertThat(repository.findById(ScanJobId.of(1L)).orElseThrow().getState()).isEqualTo(ScanJobState.PENDING);
    }

    @Test
    void findNextDue_returnsTheOldestDueJobsOfTheQueueUpToTheLimit() {
        leased(1L, NOW.minusSeconds(500));
        saved(newJob(2L, ScanQueue.SMALL, NOW.minusSeconds(10)));
        saved(newJob(3L, ScanQueue.SMALL, NOW.minusSeconds(100)));
        saved(newJob(4L, ScanQueue.SMALL, NOW.plusSeconds(60)));
        saved(newJob(5L, ScanQueue.LARGE, NOW.minusSeconds(200)));
        saved(newJob(6L, ScanQueue.SMALL, NOW.minusSeconds(50)));

        // Job 1 is already leased, job 4 is not due yet and job 5 belongs to the other queue.
        assertThat(repository.findNextDue(ScanQueue.SMALL, NOW, 10)).extracting(ScanJob::id)
                .containsExactly(ScanJobId.of(3L), ScanJobId.of(6L), ScanJobId.of(2L));
        assertThat(repository.findNextDue(ScanQueue.SMALL, NOW, 2)).extracting(ScanJob::id)
                .containsExactly(ScanJobId.of(3L), ScanJobId.of(6L));
        assertThat(repository.findNextDue(ScanQueue.LARGE, NOW, 10)).extracting(ScanJob::id)
                .containsExactly(ScanJobId.of(5L));
    }

    @Test
    void findNextDue_isEmptyWhenNothingIsDue() {
        saved(newJob(1L, ScanQueue.SMALL, NOW.plusSeconds(60)));

        assertThat(repository.findNextDue(ScanQueue.SMALL, NOW, 10)).isEmpty();
    }

    @Test
    void saveAll_writesSeveralNewJobsTogetherWithTheirEvents() {
        ScanJob first = newJob(1L, ScanQueue.SMALL, NOW);
        ScanJob second = newJob(2L, ScanQueue.LARGE, NOW);

        repository.saveAll(List.of(first, second), ACTOR);

        assertThat(repository.findById(ScanJobId.of(1L))).isPresent();
        assertThat(repository.findById(ScanJobId.of(2L))).isPresent();
        assertThat(eventsOf(1L)).singleElement()
                .satisfies(row -> assertThat(row.getEventType()).isEqualTo("ScanJobCreated"));
        assertThat(eventsOf(2L)).hasSize(1);
    }

    @Test
    void saveAll_claimsSeveralJobsInOneCall() {
        ScanJob first = saved(newJob(1L, ScanQueue.SMALL, NOW));
        ScanJob second = saved(newJob(2L, ScanQueue.SMALL, NOW));
        first.claim(WORKER, LATER, LEASE);
        second.claim(WORKER, LATER, LEASE);

        repository.saveAll(List.of(first, second), ACTOR);

        assertThat(repository.findById(ScanJobId.of(1L)).orElseThrow()).satisfies(job -> {
            assertThat(job.getState()).isEqualTo(ScanJobState.LEASED);
            assertThat(job.version()).isEqualTo(2L);
        });
        assertThat(repository.findById(ScanJobId.of(2L)).orElseThrow().getLeaseOwner()).contains(WORKER);
        assertThat(eventsOf(2L)).extracting(FileEventRecord::getVersion).containsExactly(1L, 2L);
    }

    @Test
    void saveAll_refusesABatchThatContainsAStaleCopy() {
        saved(newJob(1L, ScanQueue.SMALL, NOW));
        saved(newJob(2L, ScanQueue.SMALL, NOW));
        ScanJob stale = repository.findById(ScanJobId.of(1L)).orElseThrow();
        ScanJob current = repository.findById(ScanJobId.of(1L)).orElseThrow();
        current.claim(WorkerId.of("worker-2"), LATER, LEASE);
        repository.save(current, ACTOR);
        ScanJob healthy = repository.findById(ScanJobId.of(2L)).orElseThrow();
        healthy.claim(WORKER, LATER, LEASE);
        stale.claim(WORKER, LATER, LEASE);

        List<ScanJob> batch = List.of(healthy, stale);

        assertThatThrownBy(() -> repository.saveAll(batch, ACTOR))
                .isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    void findExpiredLeases_returnsOnlyLeasesPastTheirExpiryOldestFirstUpToTheLimit() {
        leased(1L, NOW.minusSeconds(100));
        leased(2L, NOW.minusSeconds(200));
        leased(3L, NOW.minusSeconds(10));
        saved(newJob(4L, ScanQueue.SMALL, NOW.minusSeconds(500)));

        // Job 3 still holds a valid lease and job 4 is not leased at all.
        assertThat(repository.findExpiredLeases(NOW, 10)).extracting(ScanJob::id)
                .containsExactly(ScanJobId.of(2L), ScanJobId.of(1L));
        assertThat(repository.findExpiredLeases(NOW, 1)).extracting(ScanJob::id).containsExactly(ScanJobId.of(2L));
    }
}
