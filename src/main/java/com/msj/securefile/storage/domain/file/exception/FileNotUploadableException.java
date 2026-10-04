package com.msj.securefile.storage.domain.file.exception;

public class FileNotUploadableException extends RuntimeException {

    public FileNotUploadableException() {
        super("File does not accept content.");
    }
}