package com.msj.securefile.storage.application.result;

import java.time.Instant;

public record InitiatedUpload(String fileId, Instant uploadExpiresAt) {
}