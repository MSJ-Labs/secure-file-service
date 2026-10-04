package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Instant;

/**
 * The worker handed the job back without any fault of the file (ClamAV unreachable, shutdown).
 */
public record ScanReleased(ScanJobId jobId, FileId fileId, WorkerId worker, Instant nextAttemptAt,
                           Instant occurredOn) implements ScanJobEvent {
}