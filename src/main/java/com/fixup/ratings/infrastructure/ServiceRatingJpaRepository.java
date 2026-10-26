package com.fixup.ratings.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ServiceRatingJpaRepository extends JpaRepository<ServiceRatingEntity, UUID> {
    boolean existsByJobId(UUID jobId);

    Optional<ServiceRatingEntity> findByJobId(UUID jobId);

    List<ServiceRatingEntity> findByFixerUserIdOrderByCreatedAtDesc(UUID fixerUserId, Pageable pageable);

    interface ScoreCount {
        int getScore();
        long getTotal();
    }

    @Query("select rating.score as score, count(rating) as total from ServiceRatingEntity rating "
            + "where rating.fixerUserId = :fixerUserId group by rating.score")
    List<ScoreCount> countByScore(@Param("fixerUserId") UUID fixerUserId);
}
