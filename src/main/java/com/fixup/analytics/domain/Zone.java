package com.fixup.analytics.domain;

import java.util.Locale;

/** Las zonas se comparan normalizadas para que la caché no se fragmente por mayúsculas o espacios. */
public final class Zone {
    public static final int MAX_LENGTH = 64;

    private Zone() {
    }

    public static String normalize(String zone) {
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("A market zone is required");
        }
        var normalized = zone.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The zone exceeds " + MAX_LENGTH + " characters");
        }
        return normalized;
    }
}
