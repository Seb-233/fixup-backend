package com.fixup.ratings.domain;

import com.fixup.ratings.api.RatingConflictException;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-09: la calificación de un trabajo terminado: una puntuación general de 1 a 5, tres
 * aspectos opcionales y una reseña. Hay una sola por trabajo y no se edita: la reputación del
 * técnico no puede cambiar en silencio.
 */
public record ServiceRating(UUID id, UUID jobId, UUID requestId, UUID fixerUserId, UUID raterUserId, int score,
        Integer punctuality, Integer quality, Integer cleanliness, String comment, Instant createdAt) {

    public ServiceRating {
        requireScore(score, "score");
        if (punctuality != null) {
            requireScore(punctuality, "punctuality");
        }
        if (quality != null) {
            requireScore(quality, "quality");
        }
        if (cleanliness != null) {
            requireScore(cleanliness, "cleanliness");
        }
        if (comment != null) {
            comment = comment.isBlank() ? null : comment.trim();
            if (comment != null && comment.length() > 1000) {
                throw new RatingConflictException("COMMENT_TOO_LONG", "A review has at most 1000 characters");
            }
        }
        if (raterUserId.equals(fixerUserId)) {
            throw new RatingConflictException("SELF_RATING", "A fixer cannot rate their own work");
        }
    }

    private static void requireScore(int value, String field) {
        if (value < 1 || value > 5) {
            throw new RatingConflictException("INVALID_SCORE", field + " must be between 1 and 5");
        }
    }
}
