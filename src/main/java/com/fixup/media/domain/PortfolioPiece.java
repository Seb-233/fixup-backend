package com.fixup.media.domain;

import com.fixup.media.api.PortfolioPieceKind;
import com.fixup.media.api.PortfolioRuleException;
import com.fixup.media.api.PortfolioVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * FR-UC-17: a portfolio piece. The backend stores only the object key; the file itself is
 * uploaded by the client against a signed Supabase Storage URL, so no media content crosses
 * this API. Same decision as the verification documents of FR-UC-16.
 */
public record PortfolioPiece(UUID id, UUID fixerUserId, PortfolioPieceKind kind, String storageKey,
        String title, String description, int position, PortfolioVisibility visibility,
        Instant createdAt, Instant updatedAt) {

    public PortfolioPiece {
        Objects.requireNonNull(id);
        Objects.requireNonNull(fixerUserId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(visibility);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        storageKey = required(storageKey, "storage key", PortfolioPolicy.STORAGE_KEY_MAX);
        title = required(title, "title", PortfolioPolicy.TITLE_MAX);
        description = optional(description, PortfolioPolicy.DESCRIPTION_MAX);
        if (position < 1) {
            throw new PortfolioRuleException("INVALID_POSITION", "A portfolio position starts at 1");
        }
    }

    public static PortfolioPiece publish(UUID fixerUserId, PortfolioPieceKind kind, String storageKey,
            String title, String description, int position, Instant now) {
        return new PortfolioPiece(UUID.randomUUID(), fixerUserId, kind, storageKey, title, description,
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
        return new PortfolioPiece(id, fixerUserId, kind, storageKey, title, description, position,
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
