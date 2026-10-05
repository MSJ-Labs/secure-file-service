package com.msj.securefile.storage.application.command.claimscan;

import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Duration;

public record ClaimScanCommand(ScanQueue queue, WorkerId worker, Duration lease) {
}