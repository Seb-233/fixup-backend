package com.fixup.media.domain;

import java.util.Set;

public final class MediaContentTypeValidator {
    public static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private MediaContentTypeValidator() {
    }

    public static boolean isAllowed(String contentType) {
        return contentType != null && ALLOWED_TYPES.contains(contentType.toLowerCase().trim());
    }

    public static String extensionFor(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        return switch (contentType.toLowerCase().trim()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    public static boolean verifyMagicBytes(String contentType, byte[] bytes) {
        if (contentType == null || bytes == null) {
            return false;
        }
        String normalized = contentType.toLowerCase().trim();
        return switch (normalized) {
            case "image/jpeg" -> isJpeg(bytes);
            case "image/png" -> isPng(bytes);
            case "image/webp" -> isWebp(bytes);
            default -> false;
        };
    }

    private static boolean isJpeg(byte[] bytes) {
        if (bytes.length < 3) {
            return false;
        }
        return (bytes[0] == (byte) 0xFF) && (bytes[1] == (byte) 0xD8) && (bytes[2] == (byte) 0xFF);
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < 8) {
            return false;
        }
        return (bytes[0] == (byte) 0x89) && (bytes[1] == (byte) 0x50)
                && (bytes[2] == (byte) 0x4E) && (bytes[3] == (byte) 0x47)
                && (bytes[4] == (byte) 0x0D) && (bytes[5] == (byte) 0x0A)
                && (bytes[6] == (byte) 0x1A) && (bytes[7] == (byte) 0x0A);
    }

    private static boolean isWebp(byte[] bytes) {
        if (bytes.length < 12) {
            return false;
        }
        // RIFF header: bytes 0..3: 0x52, 0x49, 0x46, 0x46
        boolean riff = (bytes[0] == (byte) 0x52) && (bytes[1] == (byte) 0x49)
                && (bytes[2] == (byte) 0x46) && (bytes[3] == (byte) 0x46);
        // WEBP header: bytes 8..11: 0x57, 0x45, 0x42, 0x50
        boolean webp = (bytes[8] == (byte) 0x57) && (bytes[9] == (byte) 0x45)
                && (bytes[10] == (byte) 0x42) && (bytes[11] == (byte) 0x50);
        return riff && webp;
    }
}
