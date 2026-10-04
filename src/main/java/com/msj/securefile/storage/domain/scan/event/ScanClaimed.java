package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Instant;

/**
 * A worker took the job. The attempt number counts failed attempts plus this one; a release does not consume one.
 */
public record ScanClaimed(ScanJobId jobId, FileId fileId, WorkerId worker, int attemptNumber,
                          Instant leaseExpiresAt, Instant occurredOn) implements ScanJobEvent {
}