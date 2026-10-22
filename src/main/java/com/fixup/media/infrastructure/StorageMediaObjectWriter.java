package com.fixup.media.infrastructure;

import com.fixup.media.api.MediaObjectWriter;
import com.fixup.media.application.ObjectStorage;
import org.springframework.stereotype.Component;

@Component
class StorageMediaObjectWriter implements MediaObjectWriter {
    private final ObjectStorage storage;

    StorageMediaObjectWriter(ObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public boolean exists(String objectKey) {
        return storage.inspect(objectKey).exists();
    }

    @Override
    public void put(String objectKey, String contentType, byte[] bytes) {
        storage.putObject(objectKey, contentType, bytes);
    }
}
