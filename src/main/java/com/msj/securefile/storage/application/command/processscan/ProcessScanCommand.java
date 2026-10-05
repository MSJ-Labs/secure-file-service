package com.msj.securefile.storage.application.command.processscan;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Duration;

/**
 * unavailableDelay is how long the job waits when the scanner cannot be reached; the worker's configuration owns it.
 */
public record ProcessScanCommand(ScanJobId jobId, FileId fileId, WorkerId worker, Duration unavailableDelay) {
}