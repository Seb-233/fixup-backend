package com.fixup.messaging.web;

import com.fixup.messaging.api.ChatAccessDeniedException;
import com.fixup.messaging.api.ChatConflictException;
import com.fixup.shared.errors.ErrorResponse;
import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class MessagingErrorHandler {
    @ExceptionHandler(ChatAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("ACCESS_DENIED", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(ChatConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ChatConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }
}
