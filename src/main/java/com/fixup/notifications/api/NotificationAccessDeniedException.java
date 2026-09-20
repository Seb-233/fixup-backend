package com.fixup.notifications.api;

public class NotificationAccessDeniedException extends RuntimeException {
    public NotificationAccessDeniedException() {
        super("Notification not accessible to the caller");
    }
}
