package com.fixup.fixers.api;

/** The requested transition is not valid for the current verification state. */
public class FixerVerificationConflictException extends RuntimeException {
    private final String code;

    public FixerVerificationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
