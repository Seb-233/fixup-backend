package com.fixup.media.api;

public class MediaTypeNotAllowedException extends MediaException {
    public MediaTypeNotAllowedException(String message) {
        super(415, "MEDIA_TYPE_NOT_ALLOWED", message);
    }
}
