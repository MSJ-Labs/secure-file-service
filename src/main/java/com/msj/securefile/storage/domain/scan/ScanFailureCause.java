package com.msj.securefile.storage.domain.scan;

/**
 * How an attempt came to fail: the worker said so, or it stopped heartbeating and the lease ran out.
 */
public enum ScanFailureCause {
    REPORTED,
    LEASE_EXPIRED
}