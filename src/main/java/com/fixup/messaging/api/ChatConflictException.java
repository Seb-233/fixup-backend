package com.fixup.messaging.api;

/** The chat is not available yet for the current state of the repair request it belongs to. */
public class ChatConflictException extends RuntimeException {
    private final String code;

    public ChatConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
