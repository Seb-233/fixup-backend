package com.fixup.ratings.application;

import com.fixup.fixers.api.FixerReputation;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.jobs.api.JobDirectory;
import com.fixup.jobs.api.JobStatus;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.properties.api.PropertyWorkHistory;
import com.fixup.ratings.api.RatingAccessDeniedException;
import com.fixup.ratings.api.RatingConflictException;
import com.fixup.ratings.domain.ServiceRating;
import com.fixup.ratings.domain.ServiceRatings;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-09: el cliente califica un trabajo terminado. Lo califica el arrendatario que reportó la
 * falla o, si la reportó el propietario o su administrador, quien decide sobre el inmueble. En la
 * misma transacción se recalcula el promedio público del técnico y la calificación entra al
 * historial del inmueble, que es lo que sostiene el sello Fix&Rent (FR-UC-07).
 */
@Service
public class RateService {
    private final ServiceRatings ratings;
    private final JobDirectory jobs;
    private final RepairRequestDirectory requests;
    private final FixerReputation reputation;
    private final PropertyWorkHistory workHistory;
    private final ApplicationEventPublisher events;

    RateService(ServiceRatings ratings, JobDirectory jobs, RepairRequestDirectory requests, FixerReputation reputation,
            PropertyWorkHistory workHistory, ApplicationEventPublisher events) {
        this.ratings = ratings;
        this.jobs = jobs;
        this.requests = requests;
        this.reputation = reputation;
        this.workHistory = workHistory;
        this.events = events;
    }

    @Transactional
    public ServiceRating execute(CurrentActor actor, UUID jobId, int score, Integer punctuality, Integer quality,
            Integer cleanliness, String comment) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RatingAccessDeniedException();
        }
        var job = jobs.find(jobId).orElseThrow(RatingAccessDeniedException::new);
        RepairRequestSnapshot request;
        try {
            request = requests.require(job.requestId());
        } catch (RepairRequestNotFoundException notVisible) {
            // Under RLS a request the actor has no relation with is simply not visible.
            throw new RatingAccessDeniedException();
        }
        var userId = actor.internalUserId();
        if (!request.isDecidedBy(userId) && !userId.equals(request.requestedByUserId())) {
            throw new RatingAccessDeniedException();
        }
        if (job.status() != JobStatus.COMPLETED) {
            throw new RatingConflictException("JOB_NOT_COMPLETED", "Only a completed job can be rated");
        }
        if (ratings.existsByJob(jobId)) {
            throw new RatingConflictException("ALREADY_RATED", "This job was already rated");
        }
        var rating = new ServiceRating(UUID.randomUUID(), jobId, job.requestId(), job.fixerUserId(), userId, score,
                punctuality, quality, cleanliness, comment, Instant.now());
        ratings.create(rating);

        var summary = ratings.reputationOf(job.fixerUserId());
        reputation.recordReputation(job.fixerUserId(), summary.average(), summary.count());
        workHistory.recordRating(jobId, score);
        events.publishEvent(NotificationRequested.of(job.fixerUserId(), NotificationType.RATING_RECEIVED,
                "Recibiste una calificación de " + score + " de 5",
                rating.comment() == null ? "Un cliente calificó tu trabajo en \"" + request.title() + "\"."
                        : "\"" + rating.comment() + "\"",
                "/fixers/" + job.fixerUserId() + "/profile", rating.id(), "RATING"));
        return rating;
    }
}
