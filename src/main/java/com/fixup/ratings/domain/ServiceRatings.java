package com.fixup.ratings.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRatings {
    boolean existsByJob(UUID jobId);

    void create(ServiceRating rating);

    Optional<ServiceRating> findByJob(UUID jobId);

    List<ServiceRating> findByFixer(UUID fixerUserId, int limit);

    Reputation reputationOf(UUID fixerUserId);

    record Reputation(BigDecimal average, int count, int fiveStars, int fourStars, int threeStars, int twoStars,
            int oneStar) {
    }
}
