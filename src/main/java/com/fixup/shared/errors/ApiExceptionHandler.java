package com.fixup.shared.errors;

import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Does not expose exception details, rejected values or credentials. */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> unauthenticated(HttpServletRequest request) {
        return ResponseEntity.status(401).header("WWW-Authenticate", "Bearer")
                .body(new ErrorResponse(401, "UNAUTHENTICATED", "Authentication is required", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    /**
     * FR-UC-25 backstop: a write that an application-layer check let through was still rejected by a
     * Row-Level Security WITH CHECK policy. In normal operation this should never happen -- the
     * checks in RequestAccess/QuotationAccess already stop a caller before the database is touched --
     * so seeing it here means either an application bug or a write attempted outside the normal
     * request flow, either way worth an audited 403 instead of a bare 500. Anything else that reaches
     * this handler is unrelated to isolation and falls through to the default error handling.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> databaseAccessDenied(DataAccessException exception, HttpServletRequest request) {
        if (!isRowLevelSecurityViolation(exception)) {
            throw exception;
        }
        SecurityAuditLog.accessBlocked("RLS_POLICY_VIOLATION", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    private boolean isRowLevelSecurityViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("row-level security policy")) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorResponse> invalidRequest(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_REQUEST",
                "The request is invalid", request.getRequestURI()));
    }
}
