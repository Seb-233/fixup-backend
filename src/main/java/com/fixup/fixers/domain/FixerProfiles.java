package com.fixup.fixers.domain;

import com.fixup.fixers.api.Specialty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FixerProfiles {
    Optional<FixerProfile> findByUserId(UUID userId);

    /** Reads the profile for a decision, holding the row until the transaction ends. */
    Optional<FixerProfile> findByUserIdForUpdate(UUID userId);

    void create(FixerProfile profile);

    void update(FixerProfile profile);

    Optional<FixerCard> findCard(UUID userId);

    /** FR-UC-16: perfiles con documentos enviados que esperan decisión, los más antiguos primero. */
    List<FixerProfile> findUnderReview();

    /** Técnicos VERIFIED con cuenta activa, opcionalmente filtrados por especialidad y ciudad. */
    List<FixerCard> findVerifiedCards(Specialty specialty, String city);

    void updatePresentation(UUID userId, String city, String bio, Integer yearsExperience, Instant now);

    void updateReputation(UUID userId, BigDecimal ratingAverage, int ratingCount, Instant now);
}
