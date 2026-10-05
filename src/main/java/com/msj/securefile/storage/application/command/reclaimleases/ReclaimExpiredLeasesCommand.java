package com.msj.securefile.storage.application.command.reclaimleases;

public record ReclaimExpiredLeasesCommand(int batchSize) {
}