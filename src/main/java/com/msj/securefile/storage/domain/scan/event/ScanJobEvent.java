package com.msj.securefile.storage.domain.scan.event;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;

/**
 * Something that happened to a scan job. Each carries the file too, so the whole history of a file, uploads and
 * scan attempts together, is read with one query. The heartbeat is not an event: it is not a transition.
 */
public sealed interface ScanJobEvent extends DomainEvent
        permits ScanJobCreated, ScanClaimed, ScanRetryScheduled, ScanAbandoned, ScanReleased, ScanJobCompleted {

    ScanJobId jobId();

    FileId fileId();
}