package com.fixup.contracts.web;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractConflictException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LeaseContractController.class)
class LeaseContractErrorHandler {

    @ExceptionHandler(ContractNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(ContractNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CONTRACT_NOT_FOUND", "The lease contract does not exist"));
    }

    @ExceptionHandler(ContractAccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(ContractAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ACCESS_DENIED",
                        "This account cannot access or operate on this lease contract"));
    }

    @ExceptionHandler(ContractConflictException.class)
    ResponseEntity<ErrorResponse> handleConflict(ContractConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }
}
