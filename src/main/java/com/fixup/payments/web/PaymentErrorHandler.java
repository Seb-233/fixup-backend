package com.fixup.payments.web;

import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class PaymentErrorHandler {
    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ErrorResponse(404, "PAYMENT_NOT_FOUND",
                "The payment does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(PaymentAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(UnexpectedPayloadException.class)
    ResponseEntity<ErrorResponse> badRequest(UnexpectedPayloadException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_REQUEST",
                exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(PaymentConflictException.class)
    ResponseEntity<ErrorResponse> conflict(PaymentConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }
}
