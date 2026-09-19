package com.fixup.media.api;

public class MediaAlreadyAttachedException extends MediaException {
    public MediaAlreadyAttachedException(String message) {
        super(409, "MEDIA_ALREADY_ATTACHED", message);
    }
}
