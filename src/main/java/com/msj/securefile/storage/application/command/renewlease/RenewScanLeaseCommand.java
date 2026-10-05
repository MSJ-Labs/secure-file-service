package com.msj.securefile.storage.application.command.renewlease;

import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Duration;

public record RenewScanLeaseCommand(ScanJobId jobId, WorkerId worker, Duration lease) {
}