package com.msj.securefile.storage.infrastructure.adapters.persistence.audit;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.shared.infrastructure.persistence.jooq.storage.tables.records.FileEventRecord;
import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.domain.file.event.FileFoundClean;
import com.msj.securefile.storage.domain.file.event.FileUploadStarted;
import com.msj.securefile.storage.domain.file.event.UploadCompleted;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.event.ScanJobCreated;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import com.msj.securefile.support.PostgresTestDatabase;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.storage.Tables.FILE_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileEventWriterIT {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final FileId FILE_ID = FileId.of(42L);
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final Actor USER = new Actor.User(OWNER);

    private final DSLContext dsl = PostgresTestDatabase.dsl();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final FileEventWriter writer = new FileEventWriter(dsl, new AuditEventMapper(), jsonMapper);

    @BeforeEach
    void emptyTheAuditTable() {
        dsl.deleteFrom(FILE_EVENT).execute();
    }

    private List<FileEventRecord> rowsOfTheFile() {
        return dsl.selectFrom(FILE_EVENT)
                .where(FILE_EVENT.FILE_ID.eq(FILE_ID.value().toLong()))
                .orderBy(FILE_EVENT.VERSION)
                .fetch();
    }

    @Test
    void append_writesOneRowPerEventWithItsEnvelope() {
        DomainEvent started = new FileUploadStarted(FILE_ID, OWNER, "report.pdf", 1_024, NOW);

        writer.append(List.of(started), 0L, USER);

        assertThat(rowsOfTheFile()).singleElement().satisfies(row -> {
            assertThat(row.getFileId()).isEqualTo(FILE_ID.value().toLong());
            assertThat(row.getAggregateType()).isEqualTo("FILE");
            assertThat(row.getAggregateId()).isEqualTo(FILE_ID.value().toLong());
            assertThat(row.getEventType()).isEqualTo("FileUploadStarted");
            assertThat(row.getOccurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void append_numbersTheEventsFromTheLoadedVersion() {
        List<DomainEvent> events = List.of(
                new UploadCompleted(FILE_ID, Sha256.of("a".repeat(64)), 1_024, NOW),
                new FileFoundClean(FILE_ID, NOW.plusSeconds(1)));

        writer.append(events, 4L, USER);

        // Loaded version 4 and two new events: 5 and 6, in the order the events happened.
        assertThat(rowsOfTheFile()).extracting(FileEventRecord::getVersion).containsExactly(5L, 6L);
        assertThat(rowsOfTheFile()).extracting(FileEventRecord::getEventType)
                .containsExactly("UploadCompleted", "FileFoundClean");
    }

    @Test
    void append_storesThePayloadAsJson() {
        writer.append(List.of(new FileUploadStarted(FILE_ID, OWNER, "report.pdf", 1_024, NOW)), 0L, USER);

        JsonNode payload = jsonMapper.readTree(rowsOfTheFile().getFirst().getPayload().data());

        assertThat(payload.get("name").asString()).isEqualTo("report.pdf");
        assertThat(payload.get("declaredSize").asLong()).isEqualTo(1_024L);
        assertThat(payload.get("ownerId").asString()).isEqualTo(OWNER.value().toString());
    }

    @Test
    void append_recordsTheCallerAsAUser() {
        writer.append(List.of(new FileFoundClean(FILE_ID, NOW)), 0L, USER);

        assertThat(rowsOfTheFile()).singleElement().satisfies(row -> {
            assertThat(row.getActorType()).isEqualTo("USER");
            assertThat(row.getActorId()).isEqualTo(OWNER.value().toString());
        });
    }

    @Test
    void append_recordsAScanWorkerAsAWorker() {
        writer.append(List.of(new FileFoundClean(FILE_ID, NOW)), 0L, new Actor.Worker(WorkerId.of("worker-1")));

        assertThat(rowsOfTheFile()).singleElement().satisfies(row -> {
            assertThat(row.getActorType()).isEqualTo("WORKER");
            assertThat(row.getActorId()).isEqualTo("worker-1");
        });
    }

    @Test
    void append_recordsTheSystemWithoutAnId() {
        writer.append(List.of(new FileFoundClean(FILE_ID, NOW)), 0L, new Actor.System());

        assertThat(rowsOfTheFile()).singleElement().satisfies(row -> {
            assertThat(row.getActorType()).isEqualTo("SYSTEM");
            assertThat(row.getActorId()).isNull();
        });
    }

    @Test
    void append_filesAScanJobEventUnderItsJobAndItsFile() {
        ScanJobId jobId = ScanJobId.of(99L);

        writer.append(List.of(new ScanJobCreated(jobId, FILE_ID, ScanQueue.SMALL, NOW)), 0L, USER);

        assertThat(rowsOfTheFile()).singleElement().satisfies(row -> {
            assertThat(row.getAggregateType()).isEqualTo("SCAN_JOB");
            assertThat(row.getAggregateId()).isEqualTo(jobId.value().toLong());
            assertThat(row.getFileId()).isEqualTo(FILE_ID.value().toLong());
        });
    }

    @Test
    void append_refusesTwoEventsAtTheSameVersionOfTheSameAggregate() {
        writer.append(List.of(new FileFoundClean(FILE_ID, NOW)), 0L, USER);

        // A second writer that loaded the same version: the unique constraint is what stops it.
        assertThatThrownBy(() -> writer.append(List.of(new FileFoundClean(FILE_ID, NOW)), 0L, USER))
                .isInstanceOf(IntegrityConstraintViolationException.class);
        assertThat(rowsOfTheFile()).hasSize(1);
    }

    @Test
    void appendAll_writesTheEventsOfSeveralAggregatesEachNumberedFromItsOwnVersion() {
        FileId other = FileId.of(43L);
        FileEventWriter.EventBatch first = new FileEventWriter.EventBatch(
                List.of(new FileFoundClean(FILE_ID, NOW)), 4L);
        FileEventWriter.EventBatch second = new FileEventWriter.EventBatch(
                List.of(new FileFoundClean(other, NOW), new FileFoundClean(other, NOW.plusSeconds(1))), 0L);

        writer.appendAll(List.of(first, second), USER);

        assertThat(rowsOfTheFile()).extracting(FileEventRecord::getVersion).containsExactly(5L);
        assertThat(dsl.selectFrom(FILE_EVENT).where(FILE_EVENT.FILE_ID.eq(other.value().toLong()))
                .orderBy(FILE_EVENT.VERSION).fetch())
                .extracting(FileEventRecord::getVersion).containsExactly(1L, 2L);
    }

    @Test
    void appendAll_doesNothingWhenNoBatchHasAnEvent() {
        writer.appendAll(List.of(new FileEventWriter.EventBatch(List.of(), 3L)), USER);

        assertThat(rowsOfTheFile()).isEmpty();
    }

    @Test
    void append_doesNothingWhenThereIsNoEvent() {
        writer.append(List.of(), 3L, USER);

        assertThat(rowsOfTheFile()).isEmpty();
    }
}
