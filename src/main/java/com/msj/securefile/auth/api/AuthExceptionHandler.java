package com.msj.securefile.auth.api;

import com.msj.securefile.auth.domain.token.InvalidRefreshTokenException;
import com.msj.securefile.auth.domain.user.AccountLockedException;
import com.msj.securefile.auth.domain.user.EmailAlreadyExistsException;
import com.msj.securefile.auth.domain.user.InvalidCredentialsException;
import com.msj.securefile.auth.domain.user.UserNotFoundException;
import com.msj.securefile.auth.domain.user.UsernameAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps authentication failures to RFC 9457 problem details.
 * The credential errors use fixed messages: the API must not reveal which usernames exist or in what state
 * an account is.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler({UsernameAlreadyExistsException.class, EmailAlreadyExistsException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ProblemDetail invalidRefreshToken(InvalidRefreshTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    ProblemDetail locked(AccountLockedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, e.getMessage());
    }

    // The caller holds a valid token for an account that no longer exists
    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail userNotFound(UserNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "User not found");
    }
}