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
import org.springframework.web.HttpRequestMethodNotSupportedException;  // ✅ ADDED
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

    // ============================================================
    // ✅ 405 METHOD NOT ALLOWED (e.g., GET on POST-only endpoint)
    // ============================================================
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ResponseBody
    public ErrorResponse handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                  HttpServletRequest request) {
        String supported = (e.getSupportedMethods() != null && e.getSupportedMethods().length > 0)
                ? String.join(", ", e.getSupportedMethods())
                : "POST";

        // ✅ Log at DEBUG – this is normal HTTP behaviour, not an application error
        log.debug("Method {} not allowed for {} (supported: {})",
                e.getMethod(), request.getRequestURI(), supported);

        return new ErrorResponse(
                "HTTP-405",
                String.format("Method %s is not supported for this endpoint. Use: %s",
                        e.getMethod(), supported),
                getCorrelationId(request),
                Instant.now(),
                null
        );
    }

    // ============================================================
    // 500 – Catch-all
    // ============================================================
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public ErrorResponse handleGeneric(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception", e);
        return new ErrorResponse("SYS-001", "Internal server error",
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
                "The rule was updated by another user. Please refresh and try again.",
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
        return new ErrorResponse("PAY-002", "Invoice not found",
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

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e, HttpServletRequest request) {
        Throwable cause = e.getCause();
        String message = cause != null ? cause.getMessage() : e.getMessage();
        if (message == null) {
            message = "Request error";
        }

        String code = "REQ-001";
        HttpStatus status = HttpStatus.BAD_REQUEST;

        if (message.contains("Invoice not found")) {
            code = "PAY-002";
            status = HttpStatus.NOT_FOUND;
        } else if (message.contains("Amount")) {
            code = "PAY-006";
            status = HttpStatus.BAD_REQUEST;
        } else if (message.contains("Currency")) {
            code = "PAY-007";
            status = HttpStatus.BAD_REQUEST;
        } else if (message.contains("not approved")) {
            code = "PAY-003";
            status = HttpStatus.BAD_REQUEST;
        }

        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message,
                        getCorrelationId(request), Instant.now(), null));
    }

    private String getCorrelationId(HttpServletRequest request) {
        String correlationId = (String) request.getAttribute("correlationId");
        if (correlationId == null) {
            correlationId = request.getHeader("X-Correlation-ID");
        }
        return correlationId != null ? correlationId : "unknown";
    }
}