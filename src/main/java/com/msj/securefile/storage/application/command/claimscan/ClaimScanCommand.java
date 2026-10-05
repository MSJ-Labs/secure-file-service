package com.msj.securefile.storage.application.command.claimscan;

import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Duration;

/**
 * limit is the number of free slots of the worker: it claims no more than it can start right away, because a lease
 * runs from the moment of the claim and a job left waiting would see it expire.
 */
public record ClaimScanCommand(ScanQueue queue, WorkerId worker, Duration lease, int limit) {
}
