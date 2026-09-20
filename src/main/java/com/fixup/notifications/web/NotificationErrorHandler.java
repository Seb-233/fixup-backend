package com.fixup.notifications.web;

import com.fixup.notifications.api.NotificationAccessDeniedException;
import com.fixup.notifications.api.NotificationNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NotificationController.class)
class NotificationErrorHandler {
    @ExceptionHandler(NotificationAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ErrorResponse(404, "NOTIFICATION_NOT_FOUND",
                "The notification does not exist", request.getRequestURI()));
    }
}
