package com.fixup.jobs.web;

import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.api.JobConflictException;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class JobErrorHandler {
    @ExceptionHandler(JobAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    @ExceptionHandler(JobNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ErrorResponse(404, "JOB_NOT_FOUND",
                "The job does not exist", request.getRequestURI()));
    }

    @ExceptionHandler(JobConflictException.class)
    ResponseEntity<ErrorResponse> conflict(JobConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(409, exception.code(),
                exception.getMessage(), request.getRequestURI()));
    }
}
