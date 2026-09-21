package com.fixup.jobs.api;

/** The requested transition is not valid for the current state of the job. */
public class JobConflictException extends RuntimeException {
    private final String code;

    public JobConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
