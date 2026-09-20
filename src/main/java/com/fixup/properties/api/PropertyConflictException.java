package com.fixup.properties.api;

public class PropertyConflictException extends RuntimeException {
    private final String code;

    public PropertyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
