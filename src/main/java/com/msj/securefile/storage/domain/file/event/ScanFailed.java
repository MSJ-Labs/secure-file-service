package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;

import java.time.Instant;

public record ScanFailed(FileId fileId, Instant occurredOn) implements FileEvent {
}