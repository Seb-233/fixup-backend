package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerCard;
import com.fixup.fixers.domain.FixerProfile;
import com.fixup.fixers.domain.FixerProfiles;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaFixerProfiles implements FixerProfiles {
    private final FixerProfileJpaRepository repository;

    JpaFixerProfiles(FixerProfileJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<FixerProfile> findByUserId(UUID userId) {
        return repository.findById(userId).map(FixerProfileEntity::toDomain);
    }

    @Override
    public Optional<FixerProfile> findByUserIdForUpdate(UUID userId) {
        return repository.findAndLockByUserId(userId).map(FixerProfileEntity::toDomain);
    }

    @Override
    public void create(FixerProfile profile) {
        repository.saveAndFlush(FixerProfileEntity.from(profile));
    }

    @Override
    public void update(FixerProfile profile) {
        // The row is locked for the decision so two reviewers cannot overwrite each other.
        var entity = repository.findAndLockByUserId(profile.userId())
                .orElseThrow(() -> new FixerVerificationConflictException("PROFILE_NOT_FOUND",
                        "There is no fixer profile to update"));
        entity.apply(profile);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<FixerCard> findCard(UUID userId) {
        return repository.findById(userId).map(FixerProfileEntity::toCard);
    }

    @Override
    public List<FixerProfile> findUnderReview() {
        return repository.findUnderReview().stream().map(FixerProfileEntity::toDomain).toList();
    }

    @Override
    public List<FixerCard> findVerifiedCards(Specialty specialty, String city) {
        return repository.findVerified().stream()
                .map(FixerProfileEntity::toCard)
                .filter(card -> specialty == null || card.profile().specialties().contains(specialty))
                .filter(card -> city == null || city.isBlank()
                        || (card.profile().city() != null && card.profile().city().equalsIgnoreCase(city.trim())))
                .toList();
    }

    @Override
    public void updatePresentation(UUID userId, String city, String bio, Integer yearsExperience, Instant now) {
        var entity = repository.findAndLockByUserId(userId)
                .orElseThrow(() -> new FixerVerificationConflictException("PROFILE_NOT_FOUND",
                        "There is no fixer profile to update"));
        entity.present(city, bio, yearsExperience, now);
        repository.saveAndFlush(entity);
    }

    @Override
    public void updateReputation(UUID userId, BigDecimal ratingAverage, int ratingCount, Instant now) {
        repository.findAndLockByUserId(userId).ifPresent(entity -> {
            entity.rate(ratingAverage, ratingCount, now);
            repository.saveAndFlush(entity);
        });
    }
}
