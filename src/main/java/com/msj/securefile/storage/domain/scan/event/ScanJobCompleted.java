package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Instant;

public record ScanJobCompleted(ScanJobId jobId, FileId fileId, WorkerId worker, Instant occurredOn)
        implements ScanJobEvent {
}