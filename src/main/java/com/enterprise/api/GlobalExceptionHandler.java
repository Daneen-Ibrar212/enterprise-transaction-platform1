package com.enterprise.api;

import com.enterprise.exception.DuplicateRequestConflictException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ResponseBody
    public ErrorResponse handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                  HttpServletRequest request) {
        String supported = (e.getSupportedMethods() != null && e.getSupportedMethods().length > 0)
                ? String.join(", ", e.getSupportedMethods())
                : "POST";
        return new ErrorResponse("HTTP-405",
                String.format("Method %s is not supported. Use: %s", e.getMethod(), supported),
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ErrorResponse handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return new ErrorResponse("PAY-001", "Idempotency-Key header is required",
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ErrorResponse handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        return new ErrorResponse("VAL-001", "Validation failed",
                getCorrelationId(request), Instant.now(), fieldErrors);
    }

    @ExceptionHandler(DuplicateRequestConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @ResponseBody
    public ErrorResponse handleConflict(DuplicateRequestConflictException e, HttpServletRequest request) {
        return new ErrorResponse("IDEM-001", e.getMessage(),
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    public ErrorResponse handleDbFailure(DataAccessException e, HttpServletRequest request) {
        log.error("Database error", e);
        return new ErrorResponse("DB-001", "Database unavailable",
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(OptimisticLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @ResponseBody
    public ErrorResponse handleOptimisticLock(OptimisticLockException e, HttpServletRequest request) {
        return new ErrorResponse("CONCURRENCY-001",
                "The record was updated by another user. Please refresh and try again.",
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ErrorResponse handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return new ErrorResponse("REQ-400", e.getMessage(),
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ErrorResponse handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        String code = "STATE-001";
        if (e.getMessage() != null && e.getMessage().contains("not approved")) {
            code = "PAY-003";
        }
        return new ErrorResponse(code, e.getMessage(),
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public ErrorResponse handleNoSuchElement(NoSuchElementException e, HttpServletRequest request) {
        return new ErrorResponse("PAY-002", "Resource not found",
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ErrorResponse handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return new ErrorResponse("REQ-001", "Invalid request payload",
                getCorrelationId(request), Instant.now(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public ErrorResponse handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return new ErrorResponse("NF-001", "Resource not found",
                getCorrelationId(request), Instant.now(), null);
    }

    /**
     * ✅ Fallback for raw RuntimeException.
     *
     * The previous version derived the HTTP status from the *message text*,
     * so a transient "Retry exhausted" became 400 Bad Request — which the
     * client should not retry, even though the failure was transient.
     *
     * Now: unauthorised, "not found", and state-related messages map to
     * 4xx; everything else (including infrastructure retries) maps to 5xx.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e, HttpServletRequest request) {
        Throwable cause = e.getCause();
        String message = cause != null ? cause.getMessage() : e.getMessage();
        if (message == null) message = "Request failed";

        String lower = message.toLowerCase();

        String code;
        HttpStatus status;

        if (lower.contains("not found") || lower.contains("does not exist")) {
            code = "REQ-404"; status = HttpStatus.NOT_FOUND;
        } else if (lower.contains("unauthorized") || lower.contains("not allowed")
                || lower.contains("access denied") || lower.contains("forbidden")) {
            code = "REQ-403"; status = HttpStatus.FORBIDDEN;
        } else if (lower.contains("amount") || lower.contains("currency")
                || lower.contains("not approved") || lower.contains("invalid")
                || lower.contains("cannot") || lower.contains("already")
                || lower.contains("missing") || lower.contains("required")) {
            code = "REQ-400"; status = HttpStatus.BAD_REQUEST;
        } else {
            // Transient / infrastructure / retry-exhausted / unknown
            code = "SYS-500"; status = HttpStatus.INTERNAL_SERVER_ERROR;
            log.error("Unhandled runtime failure: {}", message, e);
        }

        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message,
                        getCorrelationId(request), Instant.now(), null));
    }

    private String getCorrelationId(HttpServletRequest request) {
        String id = (String) request.getAttribute("correlationId");
        if (id == null) id = request.getHeader("X-Correlation-ID");
        return id != null ? id : "unknown";
    }
}