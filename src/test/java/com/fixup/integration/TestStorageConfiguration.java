package com.fixup.integration;

import com.fixup.media.application.ObjectStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestStorageConfiguration {
    public static final byte[] JPEG_MAGIC = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02};
    public static final byte[] PNG_MAGIC = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    public static final byte[] WEBP_MAGIC = new byte[] {0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
    public static final byte[] EXE_MAGIC = new byte[] {0x7F, 0x45, 0x4C, 0x46, 0x01, 0x01, 0x01, 0x00};

    private static final InMemoryObjectStorage INSTANCE = new InMemoryObjectStorage();

    @Bean
    @Primary
    ObjectStorage testObjectStorage() {
        return INSTANCE;
    }

    public static InMemoryObjectStorage instance() {
        return INSTANCE;
    }

    public static class InMemoryObjectStorage implements ObjectStorage {
        private final Map<String, byte[]> storage = new ConcurrentHashMap<>();
        private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
        private boolean simulateFailureOnDelete = false;

        public void put(String objectKey, byte[] bytes, String contentType) {
            storage.put(objectKey, bytes);
            contentTypes.put(objectKey, contentType);
        }

        public void setSimulateFailureOnDelete(boolean simulate) {
            this.simulateFailureOnDelete = simulate;
        }

        public boolean exists(String objectKey) {
            return storage.containsKey(objectKey);
        }

        public void clear() {
            storage.clear();
            contentTypes.clear();
            simulateFailureOnDelete = false;
        }

        @Override
        public UploadTicket createUploadTicket(String objectKey, String contentType, long sizeBytes, Duration expiration) {
            return new UploadTicket(
                    "http://localhost:9000/upload/" + objectKey,
                    "PUT",
                    Map.of("Content-Type", contentType),
                    Instant.now().plus(expiration));
        }

        @Override
        public StoredObjectMetadata inspect(String objectKey) {
            byte[] bytes = storage.get(objectKey);
            if (bytes == null) {
                return new StoredObjectMetadata(false, 0L, null, null);
            }
            return new StoredObjectMetadata(true, (long) bytes.length, contentTypes.get(objectKey), "etag");
        }

        @Override
        public byte[] readHead(String objectKey, int maxBytes) {
            byte[] bytes = storage.get(objectKey);
            if (bytes == null) {
                return new byte[0];
            }
            int len = Math.min(bytes.length, maxBytes);
            byte[] head = new byte[len];
            System.arraycopy(bytes, 0, head, 0, len);
            return head;
        }

        @Override
        public ReadTicket createReadTicket(String objectKey, Duration expiration) {
            return new ReadTicket("http://localhost:9000/read/" + objectKey, Instant.now().plus(expiration));
        }

        @Override
        public void delete(String objectKey) {
            if (simulateFailureOnDelete) {
                throw new RuntimeException("Simulated MinIO failure during deletion");
            }
            storage.remove(objectKey);
            contentTypes.remove(objectKey);
        }
    }
}
