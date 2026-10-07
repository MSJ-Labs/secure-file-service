package com.msj.securefile.storage.domain.file.exception;

public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException() {
        super("The file is larger than the allowed size.");
    }
}
