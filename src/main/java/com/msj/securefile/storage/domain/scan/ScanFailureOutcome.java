package com.msj.securefile.storage.domain.scan;

/**
 * What the job decided after a failed scan, so the caller knows whether to requeue the file or give up on it.
 */
public enum ScanFailureOutcome {
    RETRY_SCHEDULED,
    EXHAUSTED
}