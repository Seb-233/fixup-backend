package com.fixup.ratings.infrastructure;

import com.fixup.ratings.domain.ServiceRating;
import com.fixup.ratings.domain.ServiceRatings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class JpaServiceRatings implements ServiceRatings {
    private final ServiceRatingJpaRepository repository;

    JpaServiceRatings(ServiceRatingJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByJob(UUID jobId) {
        return repository.existsByJobId(jobId);
    }

    @Override
    public void create(ServiceRating rating) {
        repository.saveAndFlush(ServiceRatingEntity.from(rating));
    }

    @Override
    public Optional<ServiceRating> findByJob(UUID jobId) {
        return repository.findByJobId(jobId).map(ServiceRatingEntity::toDomain);
    }

    @Override
    public List<ServiceRating> findByFixer(UUID fixerUserId, int limit) {
        return repository.findByFixerUserIdOrderByCreatedAtDesc(fixerUserId, PageRequest.of(0, limit)).stream()
                .map(ServiceRatingEntity::toDomain).toList();
    }

    @Override
    public Reputation reputationOf(UUID fixerUserId) {
        int[] counts = new int[6];
        long sum = 0;
        int total = 0;
        for (var row : repository.countByScore(fixerUserId)) {
            counts[row.getScore()] = (int) row.getTotal();
            sum += (long) row.getScore() * row.getTotal();
            total += (int) row.getTotal();
        }
        var average = total == 0 ? null
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new Reputation(average, total, counts[5], counts[4], counts[3], counts[2], counts[1]);
    }
}
