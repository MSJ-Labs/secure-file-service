package com.msj.securefile.storage.application.command.uploadfile;

import com.msj.securefile.storage.domain.file.valueobject.FileId;

import java.io.InputStream;

public record UploadFileCommand(FileId fileId, InputStream content) {
}