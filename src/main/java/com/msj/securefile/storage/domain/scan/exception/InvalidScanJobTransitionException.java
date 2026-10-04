package com.msj.securefile.storage.domain.scan.exception;

import com.msj.securefile.storage.domain.scan.ScanJobState;

public class InvalidScanJobTransitionException extends RuntimeException {

    public InvalidScanJobTransitionException(ScanJobState from, ScanJobState to) {
        super("A scan job cannot move from " + from + " to " + to + ".");
    }
}