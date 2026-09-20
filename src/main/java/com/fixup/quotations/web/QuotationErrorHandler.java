package com.fixup.quotations.web;

import com.fixup.quotations.api.QuotationAccessDeniedException;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class QuotationErrorHandler {
    @ExceptionHandler(QuotationAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("ACCESS_DENIED", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(QuotationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        // FR-UC-25: Row-Level Security means this also fires when the quotation exists but belongs to
        // someone else's request, since it never shows up in that caller's SELECT in the first place.
        SecurityAuditLog.accessBlocked("NOT_FOUND_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(404).body(new ErrorResponse(404, "QUOTATION_NOT_FOUND",
                "The quotation does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(QuotationConflictException.class)
    ResponseEntity<ErrorResponse> conflict(QuotationConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }
}
