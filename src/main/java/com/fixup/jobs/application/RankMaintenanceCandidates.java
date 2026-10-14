package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerDirectory;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.domain.Jobs;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestSnapshot;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-11: técnicos sugeridos para una solicitud según especialidad, cercanía (misma ciudad),
 * disponibilidad (trabajos sin cerrar) y reputación. Vive en jobs porque la disponibilidad de un
 * técnico son sus trabajos abiertos, y solo jobs los conoce todos.
 *
 * <p>Es una sugerencia explicable: cada candidato trae los datos con los que se ordenó, para que el
 * administrador decida con criterio propio y no por una caja negra.
 */
@Service
public class RankMaintenanceCandidates {
    private final RepairRequestDirectory requests;
    private final FixerDirectory fixers;
    private final Jobs jobs;

    RankMaintenanceCandidates(RepairRequestDirectory requests, FixerDirectory fixers, Jobs jobs) {
        this.requests = requests;
        this.fixers = fixers;
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public List<Candidate> execute(CurrentActor actor, UUID requestId) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new JobAccessDeniedException();
        }
        RepairRequestSnapshot request;
        try {
            request = requests.require(requestId);
        } catch (RepairRequestNotFoundException notVisible) {
            throw new JobAccessDeniedException();
        }
        if (!request.isDecidedBy(actor.internalUserId()) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new JobAccessDeniedException();
        }
        var listings = fixers.verified(request.specialty(), null).stream()
                .filter(listing -> !listing.userId().equals(request.ownerUserId()))
                .toList();
        var loads = jobs.countOpenByFixers(listings.stream().map(FixerDirectory.FixerListing::userId).toList());
        var invited = requests.invitedFixers(requestId);
        return listings.stream()
                .map(listing -> {
                    boolean sameCity = listing.city() != null && request.propertyCity() != null
                            && listing.city().equalsIgnoreCase(request.propertyCity());
                    long open = loads.getOrDefault(listing.userId(), 0L);
                    return new Candidate(listing.userId(), listing.displayName(), listing.city(), sameCity, open,
                            listing.ratingAverage(), listing.ratingCount(), listing.yearsExperience(),
                            score(sameCity, open, listing.ratingAverage(), listing.ratingCount()),
                            invited.contains(listing.userId()));
                })
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(Candidate::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Misma ciudad pesa más que todo lo demás, porque una visita a otra ciudad casi nunca cumple un
     * SLA de 48 horas. Después, menos trabajos abiertos y mejor reputación, ponderada por cuántas
     * calificaciones la respaldan.
     */
    static double score(boolean sameCity, long openJobs, BigDecimal ratingAverage, int ratingCount) {
        double score = sameCity ? 50 : 0;
        score += Math.max(0, 25 - openJobs * 5);
        if (ratingAverage != null && ratingCount > 0) {
            double confidence = Math.min(1.0, ratingCount / 10.0);
            score += ratingAverage.doubleValue() * 5 * confidence;
        }
        return Math.round(score * 10) / 10.0;
    }

    public record Candidate(UUID fixerUserId, String displayName, String city, boolean sameCity, long openJobs,
            BigDecimal ratingAverage, int ratingCount, Integer yearsExperience, double score, boolean invited) {
    }
}
