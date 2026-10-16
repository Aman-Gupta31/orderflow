package com.aman.orderflow.common.exception;

import com.aman.orderflow.common.idempotency.IdempotencyConflictException;
import com.aman.orderflow.common.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                "Validation failed", request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found",
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ApiError> handleInvalidOrderState(InvalidOrderStateException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(AuthenticationException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                "Invalid email or password", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Covers AccessDeniedException thrown from inside a controller/service method
     * during MVC dispatch (e.g. OrderSecurity's ownership check via @PreAuthorize
     * on a controller method). A denial that happens at the security-filter level,
     * before MVC dispatch even starts, never reaches this advice - that path is
     * handled by RestAccessDeniedHandler instead. See
     * docs/learning-guide/05-security-jwt-auth.md for why both exist.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.FORBIDDEN.value(), "Forbidden",
                "You do not have permission to perform this action", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                "An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
