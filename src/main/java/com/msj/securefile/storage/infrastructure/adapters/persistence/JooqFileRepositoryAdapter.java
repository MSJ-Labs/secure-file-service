package com.msj.securefile.storage.infrastructure.adapters.persistence;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.shared.infrastructure.persistence.jooq.storage.tables.records.FileRecord;
import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.ConcurrentUpdateException;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
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

import static com.msj.securefile.shared.infrastructure.persistence.jooq.storage.Tables.FILE;

@Component
@RequiredArgsConstructor
public class JooqFileRepositoryAdapter implements FileRepository {

    // A file and the versions around its write: the one it was loaded with and the one it is written with.
    private record Change(SecureFile file, long loadedVersion, long newVersion) {
    }

    private final DSLContext dsl;
    private final FileEventWriter eventWriter;

    @Override
    @Transactional
    public void save(SecureFile file, Actor actor) {
        saveAll(List.of(file), actor);
    }

    /**
     * A version of 0 means the file was never stored. The new files go in one multi-row INSERT, the others in one JDBC
     * batch whose every row only applies to the version that was loaded, so a concurrent writer is detected instead of
     * overwritten. The events of all the files go in one statement, in the same transaction as the state.
     */
    @Override
    @Transactional
    public void saveAll(Collection<SecureFile> files, Actor actor) {
        List<Change> inserts = new ArrayList<>();
        List<Change> updates = new ArrayList<>();
        List<FileEventWriter.EventBatch> events = new ArrayList<>();

        for (SecureFile file : files) {
            List<DomainEvent> pulled = file.pullDomainEvents();
            if (pulled.isEmpty()) {
                continue;
            }
            long loadedVersion = file.version();
            Change change = new Change(file, loadedVersion, loadedVersion + pulled.size());
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
        InsertValuesStepN<FileRecord> insert = dsl.insertInto(FILE).columns(List.of(
                FILE.ID, FILE.OWNER_ID, FILE.ORIGINAL_NAME, FILE.SIZE_BYTES, FILE.SHA256, FILE.STATUS,
                FILE.UPLOAD_EXPIRES_AT, FILE.FAILURE_REASON, FILE.INFECTION_SIGNATURE, FILE.VERSION,
                FILE.CREATED_AT, FILE.UPDATED_AT));
        for (Change change : changes) {
            SecureFile file = change.file();
            insert = insert.values(
                    file.id().value().toLong(),
                    file.getOwner().value().toLong(),
                    file.getName(),
                    file.getDeclaredSize(),
                    file.getSha256().map(Sha256::value).orElse(null),
                    file.getStatus().name(),
                    file.getUploadExpiresAt(),
                    file.getFailureReason().map(UploadFailureReason::name).orElse(null),
                    file.getInfectionSignature().orElse(null),
                    change.newVersion(),
                    file.getCreatedAt(),
                    file.getUpdatedAt());
        }
        insert.execute();
    }

    // Only the columns that can change after the creation: the owner, the name, the size and the dates never do.
    private void updateAll(List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        BatchBindStep batch = dsl.batch(dsl.update(FILE)
                .set(FILE.STATUS, placeholder(FILE.STATUS))
                .set(FILE.SHA256, placeholder(FILE.SHA256))
                .set(FILE.FAILURE_REASON, placeholder(FILE.FAILURE_REASON))
                .set(FILE.INFECTION_SIGNATURE, placeholder(FILE.INFECTION_SIGNATURE))
                .set(FILE.UPDATED_AT, placeholder(FILE.UPDATED_AT))
                .set(FILE.VERSION, placeholder(FILE.VERSION))
                .where(FILE.ID.eq(placeholder(FILE.ID)))
                .and(FILE.VERSION.eq(placeholder(FILE.VERSION))));
        for (Change change : changes) {
            SecureFile file = change.file();
            batch.bind(
                    file.getStatus().name(),
                    file.getSha256().map(Sha256::value).orElse(null),
                    file.getFailureReason().map(UploadFailureReason::name).orElse(null),
                    file.getInfectionSignature().orElse(null),
                    file.getUpdatedAt(),
                    change.newVersion(),
                    file.id().value().toLong(),
                    change.loadedVersion());
        }
        for (int updated : batch.execute()) {
            // No row matched the version that was loaded: someone else wrote the file in the meantime.
            if (updated == 0) {
                throw new ConcurrentUpdateException();
            }
        }
    }

    // A bind variable whose value comes later, from the batch: a null literal would be compared as IS NULL.
    private static <T> Field<T> placeholder(Field<T> field) {
        return DSL.val(null, field);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SecureFile> findByIdAndOwner(FileId id, OwnerId owner) {
        return dsl.selectFrom(FILE)
                .where(FILE.ID.eq(id.value().toLong()))
                .and(FILE.OWNER_ID.eq(owner.value().toLong()))
                .fetchOptional(this::toFile);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SecureFile> findByIdForScan(FileId id) {
        return dsl.selectFrom(FILE)
                .where(FILE.ID.eq(id.value().toLong()))
                .fetchOptional(this::toFile);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecureFile> findAllByIdForScan(Collection<FileId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(FILE)
                .where(FILE.ID.in(ids.stream().map(id -> id.value().toLong()).toList()))
                .fetch(this::toFile);
    }

    /**
     * Locks the rows it returns until the end of the caller's transaction and skips the ones another reaper holds, so
     * two instances never take the same upload. A locking read cannot run in a read-only transaction.
     */
    @Override
    @Transactional
    public List<SecureFile> findExpiredUploads(Instant now, int limit) {
        return dsl.selectFrom(FILE)
                .where(FILE.STATUS.eq(FileStatus.UPLOADING.name()))
                .and(FILE.UPLOAD_EXPIRES_AT.lt(now))
                .orderBy(FILE.UPLOAD_EXPIRES_AT.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(this::toFile);
    }

    private SecureFile toFile(FileRecord row) {
        return SecureFile.reconstitute(
                FileId.of(row.getId()),
                OwnerId.of(row.getOwnerId()),
                row.getOriginalName(),
                row.getSizeBytes(),
                FileStatus.valueOf(row.getStatus()),
                row.getSha256() == null ? null : Sha256.of(row.getSha256()),
                row.getUploadExpiresAt(),
                row.getFailureReason() == null ? null : UploadFailureReason.valueOf(row.getFailureReason()),
                row.getInfectionSignature(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
