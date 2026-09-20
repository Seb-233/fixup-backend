package com.fixup.messaging.api;

/** The actor is neither the owner of the request nor its assigned fixer. */
public class ChatAccessDeniedException extends RuntimeException {
    public ChatAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}
