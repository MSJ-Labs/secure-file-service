package com.msj.securefile.storage.domain.file;

/**
 * Why an upload failed. The status only says that it failed: the cause is kept apart so the state machine
 * does not grow with every kind of failure.
 */
public enum UploadFailureReason {
    SIZE_MISMATCH,
    TIMEOUT,
    ABORTED,
    STORAGE_ERROR
}