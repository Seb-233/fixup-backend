package com.fixup.media.api;

import java.time.Instant;
import java.util.UUID;

public record SignedMediaView(
        UUID mediaId,
        String readUrl,
        Instant readUrlExpiresAt
) {
}
