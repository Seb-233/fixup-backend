package com.fixup.media.web;

import com.fixup.media.api.PortfolioRuleException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Does not expose exception details, storage keys or rejected values. */
@RestControllerAdvice
class MediaErrorHandler {
    @ExceptionHandler(PortfolioRuleException.class)
    ResponseEntity<ErrorResponse> rule(PortfolioRuleException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }
}
