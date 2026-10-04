package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanFailureCause;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Instant;

/**
 * The last allowed attempt failed: the job is over and the file will be marked as failed.
 */
public record ScanAbandoned(ScanJobId jobId, FileId fileId, WorkerId worker, int attemptNumber,
                            ScanFailureCause cause, String error, Instant occurredOn) implements ScanJobEvent {
}