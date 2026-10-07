package com.fixup.contracts.web;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.api.ContractOverlapConflictException;
import com.fixup.shared.security.SecurityAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ContractErrorHandler.class)
class ContractErrorHandler {

    @ExceptionHandler(ContractNotFoundException.class)
    ProblemDetail handleNotFound(ContractNotFoundException ex, HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ContractAccessDeniedException.class)
    ProblemDetail handleAccessDenied(ContractAccessDeniedException ex, HttpServletRequest request) {
        SecurityAuditLog.accessBlocked("DENIED_OR_NOT_VISIBLE", request.getMethod(), request.getRequestURI());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ContractOverlapConflictException.class)
    ProblemDetail handleOverlap(ContractOverlapConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Contract Overlap");
        pd.setProperty("code", ex.code());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
