package com.msj.securefile.storage.api.dto;

import com.msj.securefile.storage.application.result.FileSummary;

import java.time.Instant;

public record FileResponse(String id, String name, long size, String status, Instant createdAt) {

    public static FileResponse from(FileSummary summary) {
        return new FileResponse(summary.id(), summary.name(), summary.size(), summary.status().name(),
                summary.createdAt());
    }
}
