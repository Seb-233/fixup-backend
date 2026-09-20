package com.fixup.properties.api;

public class PropertyAccessDeniedException extends RuntimeException {
    public PropertyAccessDeniedException() {
        super("You are not allowed to access this property");
    }
}
