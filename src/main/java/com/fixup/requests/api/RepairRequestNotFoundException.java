package com.fixup.requests.api;

import java.util.UUID;

/** The request does not exist, or the caller is not entitled to learn that it does. */
public class RepairRequestNotFoundException extends RuntimeException {
    public RepairRequestNotFoundException() {
        super("The repair request does not exist");
    }

    public RepairRequestNotFoundException(UUID id) {
        super("Repair request not found: " + id);
    }
}
