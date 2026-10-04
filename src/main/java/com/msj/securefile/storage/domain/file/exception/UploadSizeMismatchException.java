package com.msj.securefile.storage.domain.file.exception;

public class UploadSizeMismatchException extends RuntimeException {

    public UploadSizeMismatchException() {
        super("Uploaded size does not match the declared size.");
    }
}