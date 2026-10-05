package com.msj.securefile.storage.application.result;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;

import java.time.Instant;

public record ClaimedScan(ScanJobId jobId, FileId fileId, Instant leaseExpiresAt) {
}