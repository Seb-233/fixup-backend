package com.fixup.media.api;

public class UploadExpiredException extends MediaException {
    public UploadExpiredException(String message) {
        super(409, "UPLOAD_EXPIRED", message);
    }
}
