package com.fixup.properties.api;

public class PropertyAccessDeniedException extends RuntimeException {
    public PropertyAccessDeniedException(String message) {
        super(message);
    }
}
