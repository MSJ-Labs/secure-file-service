package com.msj.securefile.storage.application.command.failupload;

import com.msj.securefile.storage.domain.file.UploadFailureReason;
import com.msj.securefile.storage.domain.file.valueobject.FileId;

public record FailUploadCommand(FileId fileId, UploadFailureReason reason) {
}