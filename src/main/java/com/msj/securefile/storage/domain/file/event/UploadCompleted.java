package com.msj.securefile.storage.domain.file.event;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;

import java.time.Instant;

public record UploadCompleted(FileId fileId, Sha256 digest, long size, Instant occurredOn) implements FileEvent {
}