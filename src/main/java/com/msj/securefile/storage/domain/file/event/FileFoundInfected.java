package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;

import java.time.Instant;

public record FileFoundInfected(FileId fileId, String signatureName, Instant occurredOn) implements FileEvent {
}