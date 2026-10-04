package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanFailureCause;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Instant;

/**
 * An attempt failed and another one is planned. The worker is the one that held the lease.
 */
public record ScanRetryScheduled(ScanJobId jobId, FileId fileId, WorkerId worker, int attemptNumber,
                                 ScanFailureCause cause, String error, Instant nextAttemptAt, Instant occurredOn)
        implements ScanJobEvent {
}