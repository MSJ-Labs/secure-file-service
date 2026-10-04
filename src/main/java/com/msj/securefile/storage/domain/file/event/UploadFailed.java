package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.UploadFailureReason;

import java.time.Instant;

public record UploadFailed(FileId fileId, UploadFailureReason reason, Instant occurredOn) implements FileEvent {
}