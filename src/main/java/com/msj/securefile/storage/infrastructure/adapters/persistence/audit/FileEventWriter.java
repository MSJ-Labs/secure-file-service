package com.msj.securefile.storage.infrastructure.adapters.persistence.audit;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.shared.infrastructure.persistence.jooq.storage.tables.records.FileEventRecord;
import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import io.hypersistence.tsid.TSID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStepN;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.storage.Tables.FILE_EVENT;

/**
 * Appends the events of a change to the audit table. It joins the transaction of the repository that calls it, so the
 * state and its events are written together or not at all. Rows are only ever inserted, never updated or deleted.
 */
@Component
@RequiredArgsConstructor
public class FileEventWriter {

    /**
     * The new events of one aggregate and the version it was loaded with, which is where their numbering starts.
     */
    public record EventBatch(List<DomainEvent> events, long loadedVersion) {
    }

    private final DSLContext dsl;
    private final AuditEventMapper mapper;
    private final ObjectMapper jsonMapper;

    @Transactional
    public void append(List<DomainEvent> events, long loadedVersion, Actor actor) {
        write(List.of(new EventBatch(events, loadedVersion)), actor);
    }

    /**
     * The events of every aggregate go in one statement: one round trip whatever the number of aggregates, and the rows
     * are all there or none is. Each aggregate's events are numbered after the version it was loaded with, in the order
     * they happened; two writers that loaded the same version collide on the unique constraint of (aggregate, version).
     */
    @Transactional
    public void appendAll(List<EventBatch> batches, Actor actor) {
        write(batches, actor);
    }

    // Shared by append and appendAll, each with its own transaction: calling a transactional method through this would
    // bypass the proxy that opens it.
    private void write(List<EventBatch> batches, Actor actor) {
        // The columns go in as a collection: with ten varargs, jOOQ would pick its fixed-arity overload instead.
        InsertValuesStepN<FileEventRecord> insert = dsl.insertInto(FILE_EVENT).columns(List.of(
                FILE_EVENT.ID, FILE_EVENT.FILE_ID, FILE_EVENT.AGGREGATE_TYPE, FILE_EVENT.AGGREGATE_ID,
                FILE_EVENT.VERSION, FILE_EVENT.EVENT_TYPE, FILE_EVENT.PAYLOAD, FILE_EVENT.ACTOR_TYPE,
                FILE_EVENT.ACTOR_ID, FILE_EVENT.OCCURRED_AT));

        boolean hasRows = false;
        for (EventBatch batch : batches) {
            long version = batch.loadedVersion();
            for (DomainEvent event : batch.events()) {
                AuditEvent audit = mapper.map(event);
                version++;
                insert = insert.values(
                        TSID.fast().toLong(),
                        audit.fileId(),
                        audit.aggregateType().name(),
                        audit.aggregateId(),
                        version,
                        audit.eventType(),
                        JSONB.valueOf(jsonMapper.writeValueAsString(audit.payload())),
                        actorType(actor),
                        actorId(actor),
                        audit.occurredAt());
                hasRows = true;
            }
        }
        if (hasRows) {
            insert.execute();
        }
    }

    private static String actorType(Actor actor) {
        return switch (actor) {
            case Actor.User _ -> "USER";
            case Actor.Worker _ -> "WORKER";
            case Actor.System _ -> "SYSTEM";
        };
    }

    // The system has no id: the column stays empty rather than holding a made-up one.
    private static String actorId(Actor actor) {
        return switch (actor) {
            case Actor.User(OwnerId owner) -> owner.value().toString();
            case Actor.Worker(WorkerId worker) -> worker.value();
            case Actor.System _ -> null;
        };
    }
}
