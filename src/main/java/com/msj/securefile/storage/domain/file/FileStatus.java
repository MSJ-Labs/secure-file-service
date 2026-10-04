package com.msj.securefile.storage.domain.file;

public enum FileStatus {
    UPLOADING,
    UPLOAD_FAILED,
    PENDING,
    SCANNING,
    CLEAN,
    INFECTED,
    SCAN_FAILED
}