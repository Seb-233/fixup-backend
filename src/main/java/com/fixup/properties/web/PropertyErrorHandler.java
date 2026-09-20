package com.fixup.properties.web;

import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyConflictException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = PropertyController.class)
class PropertyErrorHandler {

    @ExceptionHandler(PropertyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleNotFound(PropertyNotFoundException ex, HttpServletRequest request) {
        return new ErrorResponse(404, "PROPERTY_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(PropertyAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ErrorResponse handleForbidden(PropertyAccessDeniedException ex, HttpServletRequest request) {
        return new ErrorResponse(403, "PROPERTY_ACCESS_DENIED", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(PropertyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleConflict(PropertyConflictException ex, HttpServletRequest request) {
        return new ErrorResponse(409, ex.getCode(), ex.getMessage(), request.getRequestURI());
    }
}
