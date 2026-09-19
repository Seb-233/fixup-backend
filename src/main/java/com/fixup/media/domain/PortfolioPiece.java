package com.fixup.media.domain;

import com.fixup.media.api.PortfolioRuleException;
import com.fixup.media.api.PortfolioVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * FR-UC-17: a portfolio piece. References an owned, confirmed media asset.
 * Internal storage keys are never exposed to the client or domain.
 */
public record PortfolioPiece(
        UUID id,
        UUID fixerUserId,
        UUID mediaId,
        String title,
        String description,
        int position,
        PortfolioVisibility visibility,
        Instant createdAt,
        Instant updatedAt) {

    public PortfolioPiece {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        title = required(title, "title", PortfolioPolicy.TITLE_MAX);
        description = optional(description, PortfolioPolicy.DESCRIPTION_MAX);
        if (position < 1) {
            throw new PortfolioRuleException("INVALID_POSITION", "A portfolio position starts at 1");
        }
    }

    public static PortfolioPiece publish(UUID fixerUserId, UUID mediaId, String title,
            String description, int position, Instant now) {
        return new PortfolioPiece(UUID.randomUUID(), fixerUserId, mediaId, title, description,
                position, PortfolioVisibility.PUBLIC, now, now);
    }

    public PortfolioPiece hide(Instant now) {
        return withVisibility(PortfolioVisibility.HIDDEN, now);
    }

    public PortfolioPiece show(Instant now) {
        return withVisibility(PortfolioVisibility.PUBLIC, now);
    }

    public boolean isPublic() {
        return visibility == PortfolioVisibility.PUBLIC;
    }

    /** Only the owner curates a portfolio, even when another fixer is verified too. */
    public void requireOwnedBy(UUID candidate) {
        if (!fixerUserId.equals(candidate)) {
            throw new PortfolioRuleException("PIECE_NOT_FOUND", "There is no such piece in this portfolio");
        }
    }

    private PortfolioPiece withVisibility(PortfolioVisibility target, Instant now) {
        if (visibility == target) {
            throw new PortfolioRuleException("VISIBILITY_UNCHANGED",
                    "The piece is already " + target.name().toLowerCase());
        }
        return new PortfolioPiece(id, fixerUserId, mediaId, title, description, position,
                target, createdAt, now);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new PortfolioRuleException("INVALID_PIECE", "A portfolio piece requires a " + field);
        }
        var trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new PortfolioRuleException("INVALID_PIECE",
                    "The " + field + " exceeds " + max + " characters");
        }
        return trimmed;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new PortfolioRuleException("INVALID_PIECE",
                    "The description exceeds " + max + " characters");
        }
        return trimmed;
    }
}
