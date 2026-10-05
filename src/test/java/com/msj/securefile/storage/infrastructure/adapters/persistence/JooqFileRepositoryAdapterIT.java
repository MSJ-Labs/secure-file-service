package com.msj.securefile.storage.infrastructure.adapters.persistence;

import com.msj.securefile.shared.infrastructure.persistence.jooq.storage.tables.records.FileEventRecord;
import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.ConcurrentUpdateException;
import com.msj.securefile.storage.domain.file.FileStatus;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import com.msj.securefile.storage.infrastructure.adapters.persistence.audit.AuditEventMapper;
import com.msj.securefile.storage.infrastructure.adapters.persistence.audit.FileEventWriter;
import com.msj.securefile.support.PostgresTestDatabase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.storage.Tables.FILE_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JooqFileRepositoryAdapterIT {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final OwnerId OWNER = OwnerId.of(7L);
    private static final OwnerId OTHER_OWNER = OwnerId.of(8L);
    private static final Actor USER = new Actor.User(OWNER);
    private static final Actor SYSTEM = new Actor.System();
    private static final Sha256 DIGEST = Sha256.of("a".repeat(64));

    private final DSLContext dsl = PostgresTestDatabase.dsl();
    private final JooqFileRepositoryAdapter repository = new JooqFileRepositoryAdapter(
            dsl, new FileEventWriter(dsl, new AuditEventMapper(), JsonMapper.builder().build()));

    @BeforeEach
    void emptyTheStorageTables() {
        PostgresTestDatabase.deleteAllStorageData();
    }

    private static SecureFile uploading(long id, Instant expiresAt) {
        return SecureFile.initiate(FileId.of(id), OWNER, "report.pdf", 1_024, NOW, expiresAt);
    }

    private SecureFile saved(SecureFile file) {
        repository.save(file, USER);
        return repository.findByIdAndOwner(file.id(), file.getOwner()).orElseThrow();
    }

    private List<FileEventRecord> eventsOf(FileId id) {
        return dsl.selectFrom(FILE_EVENT)
                .where(FILE_EVENT.AGGREGATE_ID.eq(id.value().toLong()))
                .orderBy(FILE_EVENT.VERSION)
                .fetch();
    }

    @Test
    void save_thenFind_rebuildsANewFileWithEveryField() {
        SecureFile found = saved(uploading(1L, NOW.plusSeconds(3_600)));

        assertThat(found.id()).isEqualTo(FileId.of(1L));
        assertThat(found.getOwner()).isEqualTo(OWNER);
        assertThat(found.getName()).isEqualTo("report.pdf");
        assertThat(found.getDeclaredSize()).isEqualTo(1_024);
        assertThat(found.getStatus()).isEqualTo(FileStatus.UPLOADING);
        assertThat(found.getSha256()).isEmpty();
        assertThat(found.getUploadExpiresAt()).isEqualTo(NOW.plusSeconds(3_600));
        assertThat(found.getCreatedAt()).isEqualTo(NOW);
        assertThat(found.getUpdatedAt()).isEqualTo(NOW);
        // One event so far, so the stored version is 1; a rebuilt file records no event of its own.
        assertThat(found.version()).isEqualTo(1L);
        assertThat(found.pullDomainEvents()).isEmpty();
    }

    @Test
    void save_writesTheEventsOfTheChangeWithTheActor() {
        saved(uploading(1L, NOW.plusSeconds(3_600)));

        assertThat(eventsOf(FileId.of(1L))).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("FileUploadStarted");
            assertThat(row.getVersion()).isEqualTo(1L);
            assertThat(row.getActorType()).isEqualTo("USER");
        });
    }

    @Test
    void save_updatesAnExistingFileAndAdvancesItsVersion() {
        SecureFile file = saved(uploading(1L, NOW.plusSeconds(3_600)));

        file.completeUpload(DIGEST, 1_024, LATER);
        repository.save(file, USER);
        SecureFile reloaded = repository.findByIdAndOwner(file.id(), OWNER).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(reloaded.getSha256()).contains(DIGEST);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(LATER);
        assertThat(reloaded.version()).isEqualTo(2L);
        assertThat(eventsOf(file.id())).extracting(FileEventRecord::getVersion).containsExactly(1L, 2L);
    }

    @Test
    void save_keepsTheFailureReasonOfAFailedUpload() {
        SecureFile file = saved(uploading(1L, NOW.plusSeconds(3_600)));

        file.failUpload(UploadFailureReason.TIMEOUT, LATER);
        repository.save(file, SYSTEM);

        SecureFile reloaded = repository.findByIdAndOwner(file.id(), OWNER).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.UPLOAD_FAILED);
        assertThat(reloaded.getFailureReason()).contains(UploadFailureReason.TIMEOUT);
    }

    @Test
    void save_keepsTheSignatureOfAnInfectedFile() {
        SecureFile file = saved(uploading(1L, NOW.plusSeconds(3_600)));
        file.completeUpload(DIGEST, 1_024, LATER);
        file.startScan(LATER);
        file.markInfected("Win.Test.EICAR_HDB-1", LATER);
        repository.save(file, USER);

        SecureFile reloaded = repository.findByIdAndOwner(file.id(), OWNER).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.INFECTED);
        assertThat(reloaded.getInfectionSignature()).contains("Win.Test.EICAR_HDB-1");
    }

    @Test
    void save_doesNothingWhenNothingChanged() {
        SecureFile file = saved(uploading(1L, NOW.plusSeconds(3_600)));

        repository.save(file, USER);

        assertThat(repository.findByIdAndOwner(file.id(), OWNER).orElseThrow().version()).isEqualTo(1L);
        assertThat(eventsOf(file.id())).hasSize(1);
    }

    @Test
    void save_refusesAStaleCopyAndKeepsTheFirstWriterState() {
        saved(uploading(1L, NOW.plusSeconds(3_600)));
        SecureFile first = repository.findByIdAndOwner(FileId.of(1L), OWNER).orElseThrow();
        SecureFile second = repository.findByIdAndOwner(FileId.of(1L), OWNER).orElseThrow();
        first.completeUpload(DIGEST, 1_024, LATER);
        repository.save(first, USER);

        // The second copy was loaded at the same version: it would silently overwrite the first writer.
        second.failUpload(UploadFailureReason.TIMEOUT, LATER);

        assertThatThrownBy(() -> repository.save(second, SYSTEM)).isInstanceOf(ConcurrentUpdateException.class);
        assertThat(repository.findByIdAndOwner(FileId.of(1L), OWNER).orElseThrow().getStatus())
                .isEqualTo(FileStatus.PENDING);
    }

    @Test
    void findByIdAndOwner_behavesAsNotFoundForAnotherOwner() {
        saved(uploading(1L, NOW.plusSeconds(3_600)));

        assertThat(repository.findByIdAndOwner(FileId.of(1L), OTHER_OWNER)).isEmpty();
    }

    @Test
    void findByIdForScan_findsTheFileWhoeverOwnsIt() {
        saved(uploading(1L, NOW.plusSeconds(3_600)));

        assertThat(repository.findByIdForScan(FileId.of(1L))).isPresent();
        assertThat(repository.findByIdForScan(FileId.of(2L))).isEmpty();
    }

    @Test
    void findExpiredUploads_returnsOnlyUploadsPastTheirDeadlineOldestFirst() {
        // Created long ago, so every deadline below is in the future of its creation date.
        Instant created = NOW.minusSeconds(1_000);
        saved(SecureFile.initiate(FileId.of(1L), OWNER, "recent.pdf", 1, created, NOW.minusSeconds(10)));
        saved(SecureFile.initiate(FileId.of(2L), OWNER, "old.pdf", 1, created, NOW.minusSeconds(100)));
        saved(SecureFile.initiate(FileId.of(3L), OWNER, "not-yet.pdf", 1, created, NOW.plusSeconds(10)));
        SecureFile done = saved(SecureFile.initiate(FileId.of(4L), OWNER, "done.pdf", 1, created, NOW.minusSeconds(50)));
        done.completeUpload(DIGEST, 1, created);
        repository.save(done, USER);

        List<SecureFile> expired = repository.findExpiredUploads(NOW, 10);

        // A completed upload is not stuck, whatever its old deadline says.
        assertThat(expired).extracting(SecureFile::id).containsExactly(FileId.of(2L), FileId.of(1L));
    }

    @Test
    void saveAll_writesSeveralNewFilesTogetherWithTheirEvents() {
        repository.saveAll(List.of(uploading(1L, NOW.plusSeconds(3_600)), uploading(2L, NOW.plusSeconds(3_600))), USER);

        assertThat(repository.findAllByIdForScan(List.of(FileId.of(1L), FileId.of(2L)))).hasSize(2);
        assertThat(eventsOf(FileId.of(1L))).singleElement()
                .satisfies(row -> assertThat(row.getEventType()).isEqualTo("FileUploadStarted"));
        assertThat(eventsOf(FileId.of(2L))).hasSize(1);
    }

    @Test
    void saveAll_updatesStoredFilesAndInsertsNewOnesInTheSameCall() {
        SecureFile first = saved(uploading(1L, NOW.plusSeconds(3_600)));
        SecureFile second = saved(uploading(2L, NOW.plusSeconds(3_600)));
        first.completeUpload(DIGEST, 1_024, LATER);
        second.completeUpload(DIGEST, 1_024, LATER);

        repository.saveAll(List.of(first, second, uploading(3L, NOW.plusSeconds(3_600))), USER);

        assertThat(repository.findByIdAndOwner(FileId.of(1L), OWNER).orElseThrow())
                .satisfies(file -> {
                    assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
                    assertThat(file.version()).isEqualTo(2L);
                });
        assertThat(repository.findByIdAndOwner(FileId.of(2L), OWNER).orElseThrow().getStatus())
                .isEqualTo(FileStatus.PENDING);
        assertThat(repository.findByIdAndOwner(FileId.of(3L), OWNER).orElseThrow().version()).isEqualTo(1L);
        assertThat(eventsOf(FileId.of(1L))).extracting(FileEventRecord::getVersion).containsExactly(1L, 2L);
    }

    @Test
    void saveAll_refusesABatchThatContainsAStaleCopy() {
        saved(uploading(1L, NOW.plusSeconds(3_600)));
        saved(uploading(2L, NOW.plusSeconds(3_600)));
        SecureFile stale = repository.findByIdAndOwner(FileId.of(1L), OWNER).orElseThrow();
        SecureFile current = repository.findByIdAndOwner(FileId.of(1L), OWNER).orElseThrow();
        current.completeUpload(DIGEST, 1_024, LATER);
        repository.save(current, USER);
        SecureFile healthy = repository.findByIdAndOwner(FileId.of(2L), OWNER).orElseThrow();
        healthy.failUpload(UploadFailureReason.TIMEOUT, LATER);
        stale.failUpload(UploadFailureReason.TIMEOUT, LATER);

        // The caller's transaction rolls the healthy file back with it: nothing of the batch must stand.
        assertThatThrownBy(() -> repository.saveAll(List.of(healthy, stale), SYSTEM))
                .isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    void saveAll_doesNothingForAnEmptyBatch() {
        assertThatCode(() -> repository.saveAll(List.of(), USER)).doesNotThrowAnyException();
    }

    @Test
    void findAllByIdForScan_returnsOnlyTheRequestedFiles() {
        saved(uploading(1L, NOW.plusSeconds(3_600)));
        saved(uploading(2L, NOW.plusSeconds(3_600)));
        saved(uploading(3L, NOW.plusSeconds(3_600)));

        assertThat(repository.findAllByIdForScan(List.of(FileId.of(1L), FileId.of(3L), FileId.of(404L))))
                .extracting(SecureFile::id)
                .containsExactlyInAnyOrder(FileId.of(1L), FileId.of(3L));
        assertThat(repository.findAllByIdForScan(List.of())).isEmpty();
    }

    @Test
    void findExpiredUploads_returnsAtMostTheLimit() {
        Instant created = NOW.minusSeconds(1_000);
        saved(SecureFile.initiate(FileId.of(1L), OWNER, "a.pdf", 1, created, NOW.minusSeconds(10)));
        saved(SecureFile.initiate(FileId.of(2L), OWNER, "b.pdf", 1, created, NOW.minusSeconds(100)));

        assertThat(repository.findExpiredUploads(NOW, 1)).extracting(SecureFile::id).containsExactly(FileId.of(2L));
    }
}
