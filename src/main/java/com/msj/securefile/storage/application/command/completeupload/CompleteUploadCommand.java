package com.msj.securefile.storage.application.command.completeupload;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;

/**
 * The digest and the size are measured by the server while it streams the body, never taken from the client.
 */
public record CompleteUploadCommand(FileId fileId, Sha256 digest, long actualSize) {
}