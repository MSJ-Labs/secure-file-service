package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;

import java.time.Instant;

public record ScanJobCreated(ScanJobId jobId, FileId fileId, ScanQueue queue, Instant occurredOn)
        implements ScanJobEvent {
}