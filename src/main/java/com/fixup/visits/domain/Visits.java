package com.fixup.visits.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Visits {
    void create(Visit visit);

    void update(Visit visit);

    Optional<Visit> findById(UUID id);

    Optional<Visit> findByIdForUpdate(UUID id);

    List<Visit> findByRequest(UUID requestId);

    /** Visitas en las que el usuario participa, de la más próxima a la más lejana. */
    List<Visit> findByParticipant(UUID userId);

    /** Visitas vivas del técnico que se cruzan con la franja dada. */
    List<Visit> findActiveForFixerBetween(UUID fixerUserId, Instant from, Instant to);

    /** FR-UC-03: visitas confirmadas que empiezan antes de {@code until} y aún no tienen recordatorio. */
    List<Visit> findDueForReminder(Instant from, Instant until);
}
