package com.msj.securefile.storage.api;

import com.msj.securefile.storage.application.port.out.FileStorageException;
import com.msj.securefile.storage.application.port.out.UploadInterruptedException;
import com.msj.securefile.storage.application.port.out.UploadTooLargeException;
import com.msj.securefile.storage.domain.file.exception.FileNotDownloadableException;
import com.msj.securefile.storage.domain.file.exception.FileNotUploadableException;
import com.msj.securefile.storage.domain.file.exception.FileTooLargeException;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import com.msj.securefile.storage.domain.file.exception.UploadSizeMismatchException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps the storage failures to RFC 9457 problem details. A file of someone else and an unknown file give the same
 * answer, and no message carries a path, a bucket or any content.
 */
// Before Spring's own handler of problem details (order 0), which would answer 400 to a malformed id.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = FileController.class)
public class StorageExceptionHandler {

    @ExceptionHandler(SecureFileNotFoundException.class)
    ProblemDetail notFound(SecureFileNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "File not found.");
    }

    // A text that is not an id cannot belong to anyone: it behaves as an unknown file.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail invalidArgument(MethodArgumentTypeMismatchException e) {
        if (FileId.class.equals(e.getRequiredType())) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "File not found.");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request.");
    }

    // The size of an upload comes from Content-Length: a body of unknown length (chunked) is refused up front.
    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(e.getHeaderName())) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.LENGTH_REQUIRED, "The Content-Length header is required.");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request.");
    }

    @ExceptionHandler({FileNotDownloadableException.class, FileNotUploadableException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({FileTooLargeException.class, UploadTooLargeException.class})
    ProblemDetail tooLarge(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
    }

    // The domain refuses a name that is blank or too long; its message names the rule, never user data. Kept here and
    // not in the controller: a handler of the controller is tried first and would also catch the cause of a failed
    // conversion (the id of the URL), hiding the 404 below.
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({UploadSizeMismatchException.class, UploadInterruptedException.class})
    ProblemDetail badUpload(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(FileStorageException.class)
    ProblemDetail storageUnavailable(FileStorageException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The storage is unavailable.");
    }
}
