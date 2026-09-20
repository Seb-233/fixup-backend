package com.fixup.requests.api;

/** The requested transition is not valid for the current state of the repair request. */
public class RepairRequestConflictException extends RuntimeException {
    private final String code;

    public RepairRequestConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
