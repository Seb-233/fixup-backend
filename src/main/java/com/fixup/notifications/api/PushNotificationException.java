package com.fixup.notifications.api;

/** A push delivery attempt failed. Always recoverable from the caller's point of view: best-effort. */
public class PushNotificationException extends RuntimeException {
    public PushNotificationException(String message, Throwable cause) {
        super(message, cause);
    }

    public PushNotificationException(String message) {
        super(message);
    }
}
