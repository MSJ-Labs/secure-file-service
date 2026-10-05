package com.msj.securefile.storage.application.command.releasescan;

import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Duration;

public record ReleaseScanCommand(ScanJobId jobId, WorkerId worker, Duration delay) {
}