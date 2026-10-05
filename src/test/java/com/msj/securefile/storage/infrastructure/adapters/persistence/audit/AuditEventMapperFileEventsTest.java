package com.msj.securefile.storage.infrastructure.adapters.persistence.audit;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.event.FileFoundClean;
import com.msj.securefile.storage.domain.file.event.FileFoundInfected;
import com.msj.securefile.storage.domain.file.event.FileUploadStarted;
import com.msj.securefile.storage.domain.file.event.ScanFailed;
import com.msj.securefile.storage.domain.file.event.ScanRequeued;
import com.msj.securefile.storage.domain.file.event.ScanStarted;
import com.msj.securefile.storage.domain.file.event.UploadCompleted;
import com.msj.securefile.storage.domain.file.event.UploadFailed;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class AuditEventMapperFileEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final FileId FILE_ID = FileId.of(42L);
    private static final OwnerId OWNER = OwnerId.of(7L);

    private final AuditEventMapper mapper = new AuditEventMapper();

    @Test
    void fileUploadStarted_keepsTheOwnerTheNameAndTheDeclaredSize() {
        AuditEvent event = mapper.map(new FileUploadStarted(FILE_ID, OWNER, "report.pdf", 1_024, NOW));

        assertThat(event.eventType()).isEqualTo("FileUploadStarted");
        assertThat(event.payload()).containsOnly(
                entry("ownerId", OWNER.value().toString()),
                entry("name", "report.pdf"),
                entry("declaredSize", 1_024L));
    }

    @Test
    void uploadCompleted_keepsTheDigestAndTheMeasuredSize() {
        Sha256 digest = Sha256.of("a".repeat(64));

        AuditEvent event = mapper.map(new UploadCompleted(FILE_ID, digest, 1_024, NOW));

        assertThat(event.eventType()).isEqualTo("UploadCompleted");
        assertThat(event.payload()).containsOnly(entry("sha256", "a".repeat(64)), entry("size", 1_024L));
    }

    @Test
    void uploadFailed_keepsTheReason() {
        AuditEvent event = mapper.map(new UploadFailed(FILE_ID, UploadFailureReason.TIMEOUT, NOW));

        assertThat(event.eventType()).isEqualTo("UploadFailed");
        assertThat(event.payload()).containsOnly(entry("reason", "TIMEOUT"));
    }

    @Test
    void fileFoundInfected_keepsTheSignature() {
        AuditEvent event = mapper.map(new FileFoundInfected(FILE_ID, "Win.Test.EICAR_HDB-1", NOW));

        assertThat(event.eventType()).isEqualTo("FileFoundInfected");
        assertThat(event.payload()).containsOnly(entry("signature", "Win.Test.EICAR_HDB-1"));
    }

    private static Stream<Arguments> eventsWithNoData() {
        return Stream.of(
                Arguments.of(new ScanStarted(FILE_ID, NOW), "ScanStarted"),
                Arguments.of(new ScanRequeued(FILE_ID, NOW), "ScanRequeued"),
                Arguments.of(new FileFoundClean(FILE_ID, NOW), "FileFoundClean"),
                Arguments.of(new ScanFailed(FILE_ID, NOW), "ScanFailed"));
    }

    @ParameterizedTest
    @MethodSource("eventsWithNoData")
    void eventsThatCarryNoDataOfTheirOwn_haveAnEmptyPayload(DomainEvent domainEvent, String expectedType) {
        AuditEvent event = mapper.map(domainEvent);

        assertThat(event.eventType()).isEqualTo(expectedType);
        assertThat(event.payload()).isEmpty();
    }

    @Test
    void everyFileEvent_isFiledUnderItsFileWithItsDate() {
        AuditEvent event = mapper.map(new FileFoundClean(FILE_ID, NOW));

        assertThat(event.aggregateType()).isEqualTo(AuditEvent.AggregateType.FILE);
        assertThat(event.aggregateId()).isEqualTo(FILE_ID.value().toLong());
        assertThat(event.fileId()).isEqualTo(FILE_ID.value().toLong());
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void anEventOfAnUnknownFamily_isRefusedRatherThanSilentlyDropped() {
        DomainEvent unknown = () -> NOW;

        assertThatThrownBy(() -> mapper.map(unknown)).isInstanceOf(IllegalArgumentException.class);
    }
}
