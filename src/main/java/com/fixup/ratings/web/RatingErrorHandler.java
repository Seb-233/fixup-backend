package com.fixup.ratings.web;

import com.fixup.ratings.api.RatingAccessDeniedException;
import com.fixup.ratings.api.RatingConflictException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = RatingErrorHandler.class)
class RatingErrorHandler {
    @ExceptionHandler(RatingAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(RatingConflictException.class)
    ResponseEntity<ErrorResponse> conflict(RatingConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(), exception.getMessage(),
                request.getRequestURI()));
    }
}
