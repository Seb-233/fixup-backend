package com.fixup.identityaccess.web;

import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class IdentityErrorHandler {
    @ExceptionHandler(IdentityProblem.class)
    ResponseEntity<ErrorResponse> handle(IdentityProblem problem, HttpServletRequest request) {
        return switch (problem.reason()) {
            case ACCESS_DENIED -> error(403, "ACCESS_DENIED",
                    "You do not have permission to perform this action", request);
            case USER_NOT_PROVISIONED -> error(409, "USER_NOT_PROVISIONED",
                    "Bootstrap the authenticated user before performing this action", request);
            case IDENTITY_CONFLICT -> error(409, "IDENTITY_CONFLICT",
                    "The identity could not be provisioned", request);
            case INVALID_PROFILE -> error(400, "INVALID_PROFILE",
                    "The initial identity profile is invalid", request);
        };
    }

    private ResponseEntity<ErrorResponse> error(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(status, code, message, request.getRequestURI()));
    }
}
