package com.fixup.fixers.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FR-UC-09, FR-UC-11 y FR-UC-16: lo que otros módulos pueden saber de un técnico verificado sin
 * leer sus documentos: especialidades, ciudad y reputación.
 */
public interface FixerDirectory {

    /** Técnicos verificados con cuenta activa; ambos filtros son opcionales. */
    List<FixerListing> verified(Specialty specialty, String city);

    Optional<FixerListing> find(UUID fixerUserId);

    record FixerListing(UUID userId, String displayName, FixerVerificationStatus status, Set<Specialty> specialties,
            String city, String bio, Integer yearsExperience, BigDecimal ratingAverage, int ratingCount) {
    }
}
