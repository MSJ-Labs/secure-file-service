package com.msj.securefile.storage.infrastructure.adapters.persistence;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.shared.infrastructure.persistence.jooq.storage.tables.records.ScanJobRecord;
import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.ConcurrentUpdateException;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanJobSnapshot;
import com.msj.securefile.storage.domain.scan.ScanJobState;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import com.msj.securefile.storage.infrastructure.adapters.persistence.audit.FileEventWriter;
import lombok.RequiredArgsConstructor;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStepN;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.storage.Tables.SCAN_JOB;

@Component
@RequiredArgsConstructor
public class JooqScanJobRepositoryAdapter implements ScanJobRepository {

    // A job and the versions around its write: the one it was loaded with and the one it is written with.
    private record Change(ScanJob job, long loadedVersion, long newVersion) {
    }

    private final DSLContext dsl;
    private final FileEventWriter eventWriter;

    @Override
    @Transactional
    public void save(ScanJob job, Actor actor) {
        write(List.of(job), actor);
    }

    /**
     * Same rule as for the files: a version of 0 means the job was never stored, the new jobs go in one multi-row
     * INSERT, the others in one JDBC batch that only applies to the version that was loaded, so two workers cannot both
     * win the same job. The events of all the jobs go in one statement, in the same transaction as the state.
     */
    @Override
    @Transactional
    public void saveAll(Collection<ScanJob> jobs, Actor actor) {
        write(jobs, actor);
    }

    // Shared by save and saveAll, each with its own transaction: calling a transactional method through this would
    // bypass the proxy that opens it.
    private void write(Collection<ScanJob> jobs, Actor actor) {
        List<Change> inserts = new ArrayList<>();
        List<Change> updates = new ArrayList<>();
        List<FileEventWriter.EventBatch> events = new ArrayList<>();

        for (ScanJob job : jobs) {
            List<DomainEvent> pulled = job.pullDomainEvents();
            if (pulled.isEmpty()) {
                continue;
            }
            long loadedVersion = job.version();
            Change change = new Change(job, loadedVersion, loadedVersion + pulled.size());
            (loadedVersion == 0 ? inserts : updates).add(change);
            events.add(new FileEventWriter.EventBatch(pulled, loadedVersion));
        }

        insertAll(inserts);
        updateAll(updates);
        eventWriter.appendAll(events, actor);
    }

    private void insertAll(List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        InsertValuesStepN<ScanJobRecord> insert = dsl.insertInto(SCAN_JOB).columns(List.of(
                SCAN_JOB.ID, SCAN_JOB.FILE_ID, SCAN_JOB.QUEUE, SCAN_JOB.STATE, SCAN_JOB.ATTEMPTS,
                SCAN_JOB.NEXT_ATTEMPT_AT, SCAN_JOB.LEASE_OWNER, SCAN_JOB.LEASE_EXPIRES_AT, SCAN_JOB.LAST_ERROR,
                SCAN_JOB.VERSION, SCAN_JOB.CREATED_AT, SCAN_JOB.UPDATED_AT));
        for (Change change : changes) {
            ScanJob job = change.job();
            insert = insert.values(
                    job.id().value().toLong(),
                    job.getFileId().value().toLong(),
                    job.getQueue().name(),
                    job.getState().name(),
                    job.getAttempts(),
                    job.getNextAttemptAt(),
                    job.getLeaseOwner().map(WorkerId::value).orElse(null),
                    job.getLeaseExpiresAt().orElse(null),
                    job.getLastError().orElse(null),
                    change.newVersion(),
                    job.getCreatedAt(),
                    job.getUpdatedAt());
        }
        insert.execute();
    }

    // The file, the queue and the creation date never change after the job exists.
    private void updateAll(List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        BatchBindStep batch = dsl.batch(dsl.update(SCAN_JOB)
                .set(SCAN_JOB.STATE, placeholder(SCAN_JOB.STATE))
                .set(SCAN_JOB.ATTEMPTS, placeholder(SCAN_JOB.ATTEMPTS))
                .set(SCAN_JOB.NEXT_ATTEMPT_AT, placeholder(SCAN_JOB.NEXT_ATTEMPT_AT))
                .set(SCAN_JOB.LEASE_OWNER, placeholder(SCAN_JOB.LEASE_OWNER))
                .set(SCAN_JOB.LEASE_EXPIRES_AT, placeholder(SCAN_JOB.LEASE_EXPIRES_AT))
                .set(SCAN_JOB.LAST_ERROR, placeholder(SCAN_JOB.LAST_ERROR))
                .set(SCAN_JOB.UPDATED_AT, placeholder(SCAN_JOB.UPDATED_AT))
                .set(SCAN_JOB.VERSION, placeholder(SCAN_JOB.VERSION))
                .where(SCAN_JOB.ID.eq(placeholder(SCAN_JOB.ID)))
                .and(SCAN_JOB.VERSION.eq(placeholder(SCAN_JOB.VERSION))));
        for (Change change : changes) {
            ScanJob job = change.job();
            batch.bind(
                    job.getState().name(),
                    job.getAttempts(),
                    job.getNextAttemptAt(),
                    job.getLeaseOwner().map(WorkerId::value).orElse(null),
                    job.getLeaseExpiresAt().orElse(null),
                    job.getLastError().orElse(null),
                    job.getUpdatedAt(),
                    change.newVersion(),
                    job.id().value().toLong(),
                    change.loadedVersion());
        }
        for (int updated : batch.execute()) {
            // No row matched the version that was loaded: another worker or a reaper wrote the job in the meantime.
            if (updated == 0) {
                throw new ConcurrentUpdateException();
            }
        }
    }

    // A bind variable whose value comes later, from the batch: a null literal would be compared as IS NULL.
    private static <T> Field<T> placeholder(Field<T> field) {
        return DSL.val(null, field);
    }

    /**
     * Guarded on the lease owner, not on the version: a heartbeat is no transition, so it leaves the version alone and
     * writes no event. If the job was released, reclaimed or finished in the meantime nothing matches, and the worker
     * that sent the heartbeat learns it has lost the job.
     */
    @Override
    @Transactional
    public void renewLease(ScanJob job) {
        Instant leaseExpiresAt = job.getLeaseExpiresAt()
                .orElseThrow(() -> new IllegalArgumentException("The job holds no lease"));
        WorkerId owner = job.getLeaseOwner()
                .orElseThrow(() -> new IllegalArgumentException("The job holds no lease"));

        int updated = dsl.update(SCAN_JOB)
                .set(SCAN_JOB.LEASE_EXPIRES_AT, leaseExpiresAt)
                .set(SCAN_JOB.UPDATED_AT, job.getUpdatedAt())
                .where(SCAN_JOB.ID.eq(job.id().value().toLong()))
                .and(SCAN_JOB.STATE.eq(ScanJobState.LEASED.name()))
                .and(SCAN_JOB.LEASE_OWNER.eq(owner.value()))
                .execute();
        if (updated == 0) {
            throw new LeaseLostException();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScanJob> findById(ScanJobId id) {
        return dsl.selectFrom(SCAN_JOB)
                .where(SCAN_JOB.ID.eq(id.value().toLong()))
                .fetchOptional(this::toJob);
    }

    /**
     * The oldest due jobs of the queue, up to the number of free slots of the worker. They are locked until the end of
     * the caller's transaction and a job another worker already holds is skipped, so two workers never get the same
     * one. The lock is the one of the short claim transaction: during the scan a job is protected by its lease. A
     * locking read cannot run in a read-only transaction.
     */
    @Override
    @Transactional
    public List<ScanJob> findNextDue(ScanQueue queue, Instant now, int limit) {
        return dsl.selectFrom(SCAN_JOB)
                .where(SCAN_JOB.QUEUE.eq(queue.name()))
                .and(SCAN_JOB.STATE.eq(ScanJobState.PENDING.name()))
                .and(SCAN_JOB.NEXT_ATTEMPT_AT.le(now))
                .orderBy(SCAN_JOB.NEXT_ATTEMPT_AT.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(this::toJob);
    }

    @Override
    @Transactional
    public List<ScanJob> findExpiredLeases(Instant now, int limit) {
        return dsl.selectFrom(SCAN_JOB)
                .where(SCAN_JOB.STATE.eq(ScanJobState.LEASED.name()))
                .and(SCAN_JOB.LEASE_EXPIRES_AT.lt(now))
                .orderBy(SCAN_JOB.LEASE_EXPIRES_AT.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(this::toJob);
    }

    private ScanJob toJob(ScanJobRecord row) {
        return ScanJob.reconstitute(new ScanJobSnapshot(
                ScanJobId.of(row.getId()),
                FileId.of(row.getFileId()),
                ScanQueue.valueOf(row.getQueue()),
                ScanJobState.valueOf(row.getState()),
                row.getAttempts(),
                row.getNextAttemptAt(),
                row.getLeaseOwner() == null ? null : WorkerId.of(row.getLeaseOwner()),
                row.getLeaseExpiresAt(),
                row.getLastError(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion()));
    }
}
