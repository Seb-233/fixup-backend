package com.fixup.contracts.web;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractConflictException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LeaseContractController.class)
class LeaseContractErrorHandler {

    @ExceptionHandler(ContractNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(ContractNotFoundException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, "CONTRACT_NOT_FOUND",
                        "The lease contract does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(ContractAccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(ContractAccessDeniedException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(403, "ACCESS_DENIED",
                        "This account cannot access or operate on this lease contract",
                        request.getRequestURI()));
    }

    @ExceptionHandler(ContractConflictException.class)
    ResponseEntity<ErrorResponse> handleConflict(ContractConflictException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(409, ex.getCode(), ex.getMessage(),
                        request.getRequestURI()));
    }
}
