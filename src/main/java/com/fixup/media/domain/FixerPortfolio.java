package com.fixup.media.domain;

import com.fixup.media.api.PortfolioInsufficientPiecesException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FixerPortfolio(
        UUID fixerUserId,
        PortfolioStatus status,
        Instant publishedAt,
        Instant updatedAt) {

    public static final int MIN_VISIBLE_PIECES_TO_PUBLISH = 3;

    public FixerPortfolio {
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static FixerPortfolio initialDraft(UUID fixerUserId, Instant now) {
        return new FixerPortfolio(fixerUserId, PortfolioStatus.DRAFT, null, now);
    }

    public boolean isPublished() {
        return status == PortfolioStatus.PUBLISHED;
    }

    public FixerPortfolio publish(int visiblePiecesCount, Instant now) {
        if (visiblePiecesCount < MIN_VISIBLE_PIECES_TO_PUBLISH) {
            throw new PortfolioInsufficientPiecesException(
                    "A portfolio requires at least " + MIN_VISIBLE_PIECES_TO_PUBLISH + " active visible photos to be published");
        }
        return new FixerPortfolio(fixerUserId, PortfolioStatus.PUBLISHED, now, now);
    }

    public FixerPortfolio unpublish(Instant now) {
        return new FixerPortfolio(fixerUserId, PortfolioStatus.DRAFT, null, now);
    }

    public FixerPortfolio revertToDraftIfInsufficient(int visiblePiecesCount, Instant now) {
        if (status == PortfolioStatus.PUBLISHED && visiblePiecesCount < MIN_VISIBLE_PIECES_TO_PUBLISH) {
            return new FixerPortfolio(fixerUserId, PortfolioStatus.DRAFT, null, now);
        }
        return this;
    }
}
