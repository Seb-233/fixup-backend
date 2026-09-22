package com.fixup.properties.api;

/** Una transición de publicación que el estado actual del inmueble no admite. */
public class PropertyConflictException extends RuntimeException {
    private final String code;

    public PropertyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
