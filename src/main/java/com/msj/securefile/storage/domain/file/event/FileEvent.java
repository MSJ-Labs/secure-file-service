package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.shared.domain.DomainEvent;
import com.msj.securefile.storage.domain.file.valueobject.FileId;

/**
 * Something that happened to a file: the audit trail is made of these facts, one per transition, each carrying
 * only its own data. The set is closed on purpose, so the code that writes them down is checked by the compiler.
 */
public sealed interface FileEvent extends DomainEvent
        permits FileUploadStarted, UploadCompleted, UploadFailed, ScanStarted, ScanRequeued,
        FileFoundClean, FileFoundInfected, ScanFailed {

    FileId fileId();
}