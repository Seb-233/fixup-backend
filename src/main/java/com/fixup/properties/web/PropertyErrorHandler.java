package com.fixup.properties.web;

import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = PropertyErrorHandler.class)
class PropertyErrorHandler {

    @ExceptionHandler(PropertyNotFoundException.class)
    ProblemDetail handleNotFound(PropertyNotFoundException ex, HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PropertyAccessDeniedException.class)
    ProblemDetail handleAccessDenied(PropertyAccessDeniedException ex, HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}