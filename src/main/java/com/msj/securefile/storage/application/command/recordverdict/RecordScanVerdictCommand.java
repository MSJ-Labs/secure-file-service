package com.msj.securefile.storage.application.command.recordverdict;

import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

public record RecordScanVerdictCommand(ScanJobId jobId, WorkerId worker, ScanVerdict verdict) {
}