package com.fixup.remodelings.api;

public class RemodelingAccessDeniedException extends RuntimeException {
    public RemodelingAccessDeniedException() {
        super("The remodeling project is not accessible to the current actor");
    }
}
