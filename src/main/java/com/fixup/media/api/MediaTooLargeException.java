package com.fixup.media.api;

public class MediaTooLargeException extends MediaException {
    public MediaTooLargeException(String message) {
        super(413, "MEDIA_TOO_LARGE", message);
    }
}
