package com.fixup.remodelings.api;

import java.util.UUID;

public class RemodelingNotFoundException extends RuntimeException {
    public RemodelingNotFoundException(UUID projectId) {
        super("Remodeling project not found: " + projectId);
    }
}
