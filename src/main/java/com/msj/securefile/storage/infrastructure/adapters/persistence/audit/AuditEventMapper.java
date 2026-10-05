package com.msj.securefile.storage.infrastructure.adapters.persistence.audit;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.storage.domain.file.event.FileEvent;
import com.msj.securefile.storage.domain.file.event.FileFoundClean;
import com.msj.securefile.storage.domain.file.event.FileFoundInfected;
import com.msj.securefile.storage.domain.file.event.FileUploadStarted;
import com.msj.securefile.storage.domain.file.event.ScanFailed;
import com.msj.securefile.storage.domain.file.event.ScanRequeued;
import com.msj.securefile.storage.domain.file.event.ScanStarted;
import com.msj.securefile.storage.domain.file.event.UploadCompleted;
import com.msj.securefile.storage.domain.file.event.UploadFailed;
import com.msj.securefile.storage.domain.scan.event.ScanAbandoned;
import com.msj.securefile.storage.domain.scan.event.ScanClaimed;
import com.msj.securefile.storage.domain.scan.event.ScanJobCompleted;
import com.msj.securefile.storage.domain.scan.event.ScanJobCreated;
import com.msj.securefile.storage.domain.scan.event.ScanJobEvent;
import com.msj.securefile.storage.domain.scan.event.ScanReleased;
import com.msj.securefile.storage.domain.scan.event.ScanRetryScheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Turns the events of the domain into audit rows. An exhaustive switch over each sealed family, not a strategy per
 * event: adding an event to the domain breaks the build here until it is written down. The event type names are
 * stored forever, so they are spelled out and never derived from a class name.
 */
@Component
public class AuditEventMapper {

    public AuditEvent map(DomainEvent event) {
        if (event instanceof FileEvent fileEvent) {
            return mapFileEvent(fileEvent);
        }
        if (event instanceof ScanJobEvent scanJobEvent) {
            return mapScanJobEvent(scanJobEvent);
        }
        // Refused rather than dropped: an event missing from the audit trail must fail the write, not vanish.
        throw new IllegalArgumentException("No audit mapping for " + event.getClass().getName());
    }

    private AuditEvent mapFileEvent(FileEvent event) {
        return switch (event) {
            case FileUploadStarted e -> ofFile(e, "FileUploadStarted", Map.of(
                    "ownerId", e.owner().value().toString(),
                    "name", e.name(),
                    "declaredSize", e.declaredSize()));
            case UploadCompleted e -> ofFile(e, "UploadCompleted", Map.of(
                    "sha256", e.digest().value(),
                    "size", e.size()));
            case UploadFailed e -> ofFile(e, "UploadFailed", Map.of("reason", e.reason().name()));
            case ScanStarted e -> ofFile(e, "ScanStarted", Map.of());
            case ScanRequeued e -> ofFile(e, "ScanRequeued", Map.of());
            case FileFoundClean e -> ofFile(e, "FileFoundClean", Map.of());
            case FileFoundInfected e -> ofFile(e, "FileFoundInfected", Map.of("signature", e.signatureName()));
            case ScanFailed e -> ofFile(e, "ScanFailed", Map.of());
        };
    }

    private AuditEvent mapScanJobEvent(ScanJobEvent event) {
        return switch (event) {
            case ScanJobCreated e -> ofScanJob(e, "ScanJobCreated", Map.of("queue", e.queue().name()));
            case ScanClaimed e -> ofScanJob(e, "ScanClaimed", Map.of(
                    "worker", e.worker().value(),
                    "attemptNumber", e.attemptNumber(),
                    "leaseExpiresAt", e.leaseExpiresAt().toString()));
            case ScanRetryScheduled e -> ofScanJob(e, "ScanRetryScheduled", Map.of(
                    "worker", e.worker().value(),
                    "attemptNumber", e.attemptNumber(),
                    "cause", e.cause().name(),
                    "error", e.error(),
                    "nextAttemptAt", e.nextAttemptAt().toString()));
            case ScanAbandoned e -> ofScanJob(e, "ScanAbandoned", Map.of(
                    "worker", e.worker().value(),
                    "attemptNumber", e.attemptNumber(),
                    "cause", e.cause().name(),
                    "error", e.error()));
            case ScanReleased e -> ofScanJob(e, "ScanReleased", Map.of(
                    "worker", e.worker().value(),
                    "nextAttemptAt", e.nextAttemptAt().toString()));
            case ScanJobCompleted e -> ofScanJob(e, "ScanJobCompleted", Map.of("worker", e.worker().value()));
        };
    }

    private static AuditEvent ofFile(FileEvent event, String type, Map<String, Object> payload) {
        long fileId = event.fileId().value().toLong();
        return new AuditEvent(AuditEvent.AggregateType.FILE, fileId, fileId, type, event.occurredOn(), payload);
    }

    private static AuditEvent ofScanJob(ScanJobEvent event, String type, Map<String, Object> payload) {
        return new AuditEvent(AuditEvent.AggregateType.SCAN_JOB, event.jobId().value().toLong(),
                event.fileId().value().toLong(), type, event.occurredOn(), payload);
    }
}
