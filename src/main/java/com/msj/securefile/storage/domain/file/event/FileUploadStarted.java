package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;

import java.time.Instant;

public record FileUploadStarted(FileId fileId, OwnerId owner, String name, long declaredSize, Instant occurredOn)
        implements FileEvent {
}