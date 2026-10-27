package com.fixup.visits.infrastructure;

import com.fixup.visits.api.VisitNotFoundException;
import com.fixup.visits.domain.Visit;
import com.fixup.visits.domain.Visits;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaVisits implements Visits {
    private final VisitJpaRepository repository;

    JpaVisits(VisitJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void create(Visit visit) {
        repository.saveAndFlush(VisitEntity.from(visit));
    }

    @Override
    public void update(Visit visit) {
        var entity = repository.findAndLockById(visit.id()).orElseThrow(VisitNotFoundException::new);
        entity.apply(visit);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<Visit> findById(UUID id) {
        return repository.findById(id).map(VisitEntity::toDomain);
    }

    @Override
    public Optional<Visit> findByIdForUpdate(UUID id) {
        return repository.findAndLockById(id).map(VisitEntity::toDomain);
    }

    @Override
    public List<Visit> findByRequest(UUID requestId) {
        return repository.findByRequestIdOrderByScheduledAtAsc(requestId).stream().map(VisitEntity::toDomain).toList();
    }

    @Override
    public List<Visit> findByParticipant(UUID userId) {
        return repository.findByParticipant(userId).stream().map(VisitEntity::toDomain).toList();
    }

    @Override
    public List<Visit> findActiveForFixerBetween(UUID fixerUserId, Instant from, Instant to) {
        return repository.findActiveForFixerBetween(fixerUserId, from, to).stream().map(VisitEntity::toDomain).toList();
    }

    @Override
    public List<Visit> findDueForReminder(Instant from, Instant until) {
        return repository.findDueForReminder(from, until).stream().map(VisitEntity::toDomain).toList();
    }
}
