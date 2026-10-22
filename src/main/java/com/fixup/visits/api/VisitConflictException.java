package com.fixup.visits.api;

/** The requested transition is not valid for the current state of the visit. */
public class VisitConflictException extends RuntimeException {
    private final String code;

    public VisitConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
