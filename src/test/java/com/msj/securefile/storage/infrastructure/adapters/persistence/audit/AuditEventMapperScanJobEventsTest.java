package com.msj.securefile.storage.infrastructure.adapters.persistence.audit;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanFailureCause;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.event.ScanAbandoned;
import com.msj.securefile.storage.domain.scan.event.ScanClaimed;
import com.msj.securefile.storage.domain.scan.event.ScanJobCompleted;
import com.msj.securefile.storage.domain.scan.event.ScanJobCreated;
import com.msj.securefile.storage.domain.scan.event.ScanReleased;
import com.msj.securefile.storage.domain.scan.event.ScanRetryScheduled;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class AuditEventMapperScanJobEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-04T10:05:00Z");
    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");

    private final AuditEventMapper mapper = new AuditEventMapper();

    @Test
    void scanJobCreated_keepsTheQueue() {
        AuditEvent event = mapper.map(new ScanJobCreated(JOB_ID, FILE_ID, ScanQueue.LARGE, NOW));

        assertThat(event.eventType()).isEqualTo("ScanJobCreated");
        assertThat(event.payload()).containsOnly(entry("queue", "LARGE"));
    }

    @Test
    void scanClaimed_keepsTheWorkerTheAttemptAndTheLeaseExpiry() {
        AuditEvent event = mapper.map(new ScanClaimed(JOB_ID, FILE_ID, WORKER, 2, LATER, NOW));

        assertThat(event.eventType()).isEqualTo("ScanClaimed");
        assertThat(event.payload()).containsOnly(
                entry("worker", "worker-1"),
                entry("attemptNumber", 2),
                entry("leaseExpiresAt", "2026-10-04T10:05:00Z"));
    }

    @Test
    void scanRetryScheduled_keepsTheFailureAndTheNextAttemptDate() {
        AuditEvent event = mapper.map(new ScanRetryScheduled(JOB_ID, FILE_ID, WORKER, 1,
                ScanFailureCause.REPORTED, "clamd timed out", LATER, NOW));

        assertThat(event.eventType()).isEqualTo("ScanRetryScheduled");
        assertThat(event.payload()).containsOnly(
                entry("worker", "worker-1"),
                entry("attemptNumber", 1),
                entry("cause", "REPORTED"),
                entry("error", "clamd timed out"),
                entry("nextAttemptAt", "2026-10-04T10:05:00Z"));
    }

    @Test
    void scanAbandoned_keepsTheLastFailure() {
        AuditEvent event = mapper.map(new ScanAbandoned(JOB_ID, FILE_ID, WORKER, 3,
                ScanFailureCause.LEASE_EXPIRED, "lease expired", NOW));

        assertThat(event.eventType()).isEqualTo("ScanAbandoned");
        assertThat(event.payload()).containsOnly(
                entry("worker", "worker-1"),
                entry("attemptNumber", 3),
                entry("cause", "LEASE_EXPIRED"),
                entry("error", "lease expired"));
    }

    @Test
    void scanReleased_keepsTheWorkerAndTheNextAttemptDate() {
        AuditEvent event = mapper.map(new ScanReleased(JOB_ID, FILE_ID, WORKER, LATER, NOW));

        assertThat(event.eventType()).isEqualTo("ScanReleased");
        assertThat(event.payload()).containsOnly(
                entry("worker", "worker-1"),
                entry("nextAttemptAt", "2026-10-04T10:05:00Z"));
    }

    @Test
    void scanJobCompleted_keepsTheWorker() {
        AuditEvent event = mapper.map(new ScanJobCompleted(JOB_ID, FILE_ID, WORKER, NOW));

        assertThat(event.eventType()).isEqualTo("ScanJobCompleted");
        assertThat(event.payload()).containsOnly(entry("worker", "worker-1"));
    }

    @Test
    void everyScanJobEvent_isFiledUnderItsJobAndItsFileWithItsDate() {
        AuditEvent event = mapper.map(new ScanJobCompleted(JOB_ID, FILE_ID, WORKER, NOW));

        assertThat(event.aggregateType()).isEqualTo(AuditEvent.AggregateType.SCAN_JOB);
        assertThat(event.aggregateId()).isEqualTo(JOB_ID.value().toLong());
        // The file too, so one query on the file gives its upload and its scan attempts together.
        assertThat(event.fileId()).isEqualTo(FILE_ID.value().toLong());
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }
}
