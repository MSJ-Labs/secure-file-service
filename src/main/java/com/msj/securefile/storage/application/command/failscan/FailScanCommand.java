package com.msj.securefile.storage.application.command.failscan;

import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

public record FailScanCommand(ScanJobId jobId, WorkerId worker, String error) {
}