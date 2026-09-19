package com.fixup.requests.api;

/** The actor is not a participant of the request, or lacks the role the operation needs. */
public class RepairRequestAccessDeniedException extends RuntimeException {
    public RepairRequestAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}
