package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;

import java.time.Instant;

public record FileFoundClean(FileId fileId, Instant occurredOn) implements FileEvent {
}