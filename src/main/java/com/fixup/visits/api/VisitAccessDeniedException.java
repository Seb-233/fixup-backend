package com.fixup.visits.api;

/** The actor takes no part in the visit or its request. */
public class VisitAccessDeniedException extends RuntimeException {
    public VisitAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}
