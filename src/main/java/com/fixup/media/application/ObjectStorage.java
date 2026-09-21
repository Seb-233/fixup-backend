package com.fixup.media.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public interface ObjectStorage {
    UploadTicket createUploadTicket(String objectKey, String contentType, long sizeBytes, Duration expiration);

    StoredObjectMetadata inspect(String objectKey);

    byte[] readHead(String objectKey, int maxBytes);

    ReadTicket createReadTicket(String objectKey, Duration expiration);

    void delete(String objectKey);

    record UploadTicket(String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record StoredObjectMetadata(boolean exists, long sizeBytes, String contentType, String eTag) {
    }

    record ReadTicket(String readUrl, Instant expiresAt) {
    }
}
