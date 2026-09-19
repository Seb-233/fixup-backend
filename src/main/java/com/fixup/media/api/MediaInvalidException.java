package com.fixup.media.api;

public class MediaInvalidException extends MediaException {
    public MediaInvalidException(String message) {
        super(409, "MEDIA_INVALID", message);
    }
}
