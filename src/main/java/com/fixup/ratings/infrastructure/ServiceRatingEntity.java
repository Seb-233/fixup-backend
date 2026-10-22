package com.fixup.ratings.infrastructure;

import com.fixup.ratings.domain.ServiceRating;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_ratings")
class ServiceRatingEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "rater_user_id", nullable = false, updatable = false)
    private UUID raterUserId;
    @Column(name = "score", nullable = false, updatable = false)
    private int score;
    @Column(name = "punctuality", updatable = false)
    private Integer punctuality;
    @Column(name = "quality", updatable = false)
    private Integer quality;
    @Column(name = "cleanliness", updatable = false)
    private Integer cleanliness;
    @Column(name = "comment", length = 1000, updatable = false)
    private String comment;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ServiceRatingEntity() {
    }

    static ServiceRatingEntity from(ServiceRating rating) {
        var entity = new ServiceRatingEntity();
        entity.id = rating.id();
        entity.jobId = rating.jobId();
        entity.requestId = rating.requestId();
        entity.fixerUserId = rating.fixerUserId();
        entity.raterUserId = rating.raterUserId();
        entity.score = rating.score();
        entity.punctuality = rating.punctuality();
        entity.quality = rating.quality();
        entity.cleanliness = rating.cleanliness();
        entity.comment = rating.comment();
        entity.createdAt = rating.createdAt();
        return entity;
    }

    ServiceRating toDomain() {
        return new ServiceRating(id, jobId, requestId, fixerUserId, raterUserId, score, punctuality, quality,
                cleanliness, comment, createdAt);
    }
}
