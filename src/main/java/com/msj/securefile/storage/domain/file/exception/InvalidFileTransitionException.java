package com.msj.securefile.storage.domain.file.exception;

import com.msj.securefile.storage.domain.file.FileStatus;

public class InvalidFileTransitionException extends RuntimeException {

    public InvalidFileTransitionException(FileStatus from, FileStatus to) {
        super("A file cannot move from " + from + " to " + to + ".");
    }
}