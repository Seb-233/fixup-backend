package com.fixup.ratings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.ratings.api.RatingAccessDeniedException;
import com.fixup.ratings.domain.ServiceRating;
import com.fixup.ratings.domain.ServiceRatings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-09: las reseñas son públicas para cualquier usuario activo; el perfil del técnico las muestra. */
@Service
public class ReadRatings {
    static final int MAX_REVIEWS = 50;

    private final ServiceRatings ratings;
    private final UserDirectory users;

    ReadRatings(ServiceRatings ratings, UserDirectory users) {
        this.ratings = ratings;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public FixerReviews forFixer(CurrentActor actor, UUID fixerUserId, int limit) {
        requireActive(actor);
        var reviews = ratings.findByFixer(fixerUserId, Math.max(1, Math.min(limit, MAX_REVIEWS)));
        var names = users.displayNames(reviews.stream().map(ServiceRating::raterUserId).toList());
        return new FixerReviews(ratings.reputationOf(fixerUserId), reviews, names);
    }

    @Transactional(readOnly = true)
    public Optional<ServiceRating> forJob(CurrentActor actor, UUID jobId) {
        requireActive(actor);
        return ratings.findByJob(jobId);
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RatingAccessDeniedException();
        }
    }

    public record FixerReviews(ServiceRatings.Reputation reputation, List<ServiceRating> reviews,
            Map<UUID, String> raterNames) {
    }
}
