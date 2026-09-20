package com.fixup.requests.web;

import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RequestErrorHandler {
    @ExceptionHandler(RepairRequestAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("ACCESS_DENIED", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(RepairRequestNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        // FR-UC-25: Row-Level Security means this also fires when the request exists but belongs to
        // someone else, since a stranger's SELECT never returns that row in the first place.
        SecurityAuditLog.accessBlocked("NOT_FOUND_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(404).body(new ErrorResponse(404, "REQUEST_NOT_FOUND",
                "The repair request does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(RepairRequestConflictException.class)
    ResponseEntity<ErrorResponse> conflict(RepairRequestConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.status(400).body(new ErrorResponse(400, "INVALID_REQUEST",
                exception.getMessage() != null ? exception.getMessage() : "The request is invalid",
                request.getRequestURI()));
    }
}
