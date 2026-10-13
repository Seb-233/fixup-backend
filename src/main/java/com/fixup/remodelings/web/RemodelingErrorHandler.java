package com.fixup.remodelings.web;

import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.remodelings.api.RemodelingAccessDeniedException;
import com.fixup.remodelings.api.RemodelingConflictException;
import com.fixup.remodelings.api.RemodelingNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = RemodelingController.class)
class RemodelingErrorHandler {
    @ExceptionHandler(RemodelingAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(),
                request.getRequestURI());
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(RemodelingNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(),
                request.getRequestURI());
        return ResponseEntity.status(404).body(new ErrorResponse(404, "REMODELING_NOT_FOUND",
                "The remodeling project does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(PropertyNotFoundException.class)
    ResponseEntity<ErrorResponse> propertyNotFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ErrorResponse(404, "PROPERTY_NOT_FOUND",
                "The property does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(RemodelingConflictException.class)
    ResponseEntity<ErrorResponse> conflict(RemodelingConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }
}
