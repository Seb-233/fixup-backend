package com.fixup.remodelings.api;

public class RemodelingConflictException extends RuntimeException {
    private final String code;

    public RemodelingConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
