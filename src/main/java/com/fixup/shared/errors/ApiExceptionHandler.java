package com.fixup.shared.errors;

import jakarta.servlet.http.HttpServletRequest;
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

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorResponse> invalidRequest(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_REQUEST",
                "The request is invalid", request.getRequestURI()));
    }
}
