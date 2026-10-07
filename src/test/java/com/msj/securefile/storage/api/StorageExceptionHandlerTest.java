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
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class StorageExceptionHandlerTest {

    private final StorageExceptionHandler handler = new StorageExceptionHandler();

    // Only here to give the Spring exceptions the method parameter they need.
    @SuppressWarnings("unused")
    private void endpoint(String argument) {
    }

    private static MethodParameter parameter() throws NoSuchMethodException {
        return new MethodParameter(StorageExceptionHandlerTest.class.getDeclaredMethod("endpoint", String.class), 0);
    }

    @Test
    void anUnknownFile_isNotFound() {
        ProblemDetail problem = handler.notFound(new SecureFileNotFoundException());

        assertThat(problem.getStatus()).isEqualTo(404);
    }

    @Test
    void anIdThatCannotBeRead_behavesAsAnUnknownFile() throws Exception {
        var mismatch = new MethodArgumentTypeMismatchException("not-an-id", FileId.class, "id", parameter(), null);

        assertThat(handler.invalidArgument(mismatch).getStatus()).isEqualTo(404);
    }

    @Test
    void anotherArgumentThatCannotBeRead_isABadRequest() throws Exception {
        var mismatch = new MethodArgumentTypeMismatchException("x", Integer.class, "page", parameter(), null);

        assertThat(handler.invalidArgument(mismatch).getStatus()).isEqualTo(400);
    }

    @Test
    void aMissingContentLength_isLengthRequired() throws Exception {
        var missing = new MissingRequestHeaderException("content-length", parameter());

        assertThat(handler.missingHeader(missing).getStatus()).isEqualTo(411);
    }

    @Test
    void anotherMissingHeader_isABadRequest() throws Exception {
        var missing = new MissingRequestHeaderException("X-Other", parameter());

        assertThat(handler.missingHeader(missing).getStatus()).isEqualTo(400);
    }

    @Test
    void aFileThatCannotBeUsedInItsState_isAConflict() {
        assertThat(handler.conflict(new FileNotDownloadableException()).getStatus()).isEqualTo(409);
        assertThat(handler.conflict(new FileNotUploadableException()).getStatus()).isEqualTo(409);
    }

    @Test
    void aFileThatIsTooLarge_isRejectedWith413() {
        assertThat(handler.tooLarge(new FileTooLargeException()).getStatus()).isEqualTo(413);
        assertThat(handler.tooLarge(new UploadTooLargeException()).getStatus()).isEqualTo(413);
    }

    @Test
    void aRefusedValue_isABadRequestThatNamesTheRule() {
        ProblemDetail problem = handler.invalidRequest(new IllegalArgumentException("File name is required"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("File name is required");
    }

    @Test
    void anUploadThatDoesNotMatchWhatWasDeclared_isABadRequest() {
        assertThat(handler.badUpload(new UploadSizeMismatchException()).getStatus()).isEqualTo(400);
        assertThat(handler.badUpload(new UploadInterruptedException(new RuntimeException("closed"))).getStatus())
                .isEqualTo(400);
    }

    @Test
    void aStorageFailure_isUnavailableAndNeverShowsItsCause() {
        ProblemDetail problem = handler.storageUnavailable(new FileStorageException(new RuntimeException("bucket x")));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).doesNotContain("bucket");
    }
}
