package com.example.backend.controller;

import com.example.backend.common.ErrorCode;
import com.example.backend.common.Result;
import com.example.backend.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException error) {
        ErrorCode code = error.errorCode();
        return build(code.httpStatus(), code.code(), safe(error.getMessage(), "request failed"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> invalidBody(MethodArgumentNotValidException error) {
        String detail = error.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.code(),
                detail.isBlank() ? "invalid request body" : detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> invalidParam(ConstraintViolationException error) {
        String detail = error.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.code(),
                detail.isBlank() ? "invalid request parameters" : detail);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Result<Void>> badRequest(Exception error) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(), clientMessage(error));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.INVALID_ARGUMENT.code(), "method is not supported");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<Void>> notFound(NoSuchElementException error) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.code(), safe(error.getMessage(), "resource not found"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<Void>> forbidden(SecurityException error) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.code(), safe(error.getMessage(), "access denied"));
    }

    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<Result<Void>> asyncFailure(CompletionException error) {
        Throwable cause = error.getCause();
        while (cause instanceof CompletionException nested && nested.getCause() != null) {
            cause = nested.getCause();
        }
        if (cause instanceof BusinessException businessException) {
            return business(businessException);
        }
        if (cause instanceof NoSuchElementException notFoundException) {
            return notFound(notFoundException);
        }
        if (cause instanceof SecurityException securityException) {
            return forbidden(securityException);
        }
        if (cause instanceof IllegalArgumentException argumentException) {
            return badRequest(argumentException);
        }
        if (cause instanceof Exception exception) {
            return internalError(exception);
        }
        return internalError(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> internalError(Exception error) {
        log.error("unhandled_request_error", error);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.code(), "service unavailable");
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(Result.error(code, message));
    }

    private String clientMessage(Exception error) {
        if (error instanceof IllegalArgumentException) {
            return safe(error.getMessage(), "invalid request parameters");
        }
        return "invalid request parameters";
    }

    private String safe(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
