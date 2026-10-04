package com.msj.securefile.storage.domain.file.exception;

public class FileNotDownloadableException extends RuntimeException {

    public FileNotDownloadableException() {
        super("File is not available for download.");
    }
}