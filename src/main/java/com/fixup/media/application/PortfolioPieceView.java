package com.fixup.media.application;

import com.fixup.media.api.PortfolioVisibility;
import java.time.Instant;
import java.util.UUID;

public record PortfolioPieceView(
        UUID id,
        UUID mediaId,
        String title,
        String description,
        int position,
        PortfolioVisibility visibility,
        String readUrl,
        Instant readUrlExpiresAt,
        Instant createdAt) {
}
