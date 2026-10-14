package com.fixup.visits.web;

import com.fixup.shared.errors.ErrorResponse;
import com.fixup.shared.security.SecurityAuditLog;
import com.fixup.visits.api.VisitAccessDeniedException;
import com.fixup.visits.api.VisitConflictException;
import com.fixup.visits.api.VisitNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = VisitErrorHandler.class)
class VisitErrorHandler {
    @ExceptionHandler(VisitAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(VisitNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ErrorResponse(404, "VISIT_NOT_FOUND",
                "The visit does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(VisitConflictException.class)
    ResponseEntity<ErrorResponse> conflict(VisitConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(), exception.getMessage(),
                request.getRequestURI()));
    }
}
