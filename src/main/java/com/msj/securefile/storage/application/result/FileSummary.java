package com.msj.securefile.storage.application.result;

import com.msj.securefile.storage.domain.file.FileStatus;

import java.time.Instant;

public record FileSummary(String id, String name, long size, FileStatus status, Instant createdAt) {
}
