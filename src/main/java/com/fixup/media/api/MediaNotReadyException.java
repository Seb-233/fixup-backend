package com.fixup.media.api;

public class MediaNotReadyException extends MediaException {
    public MediaNotReadyException(String message) {
        super(409, "MEDIA_NOT_READY", message);
    }
}
