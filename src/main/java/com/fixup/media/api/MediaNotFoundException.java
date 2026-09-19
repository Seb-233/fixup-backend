package com.fixup.media.api;

public class MediaNotFoundException extends MediaException {
    public MediaNotFoundException(String message) {
        super(404, "MEDIA_NOT_FOUND", message);
    }
}
