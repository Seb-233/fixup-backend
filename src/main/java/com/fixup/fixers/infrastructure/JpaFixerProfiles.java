package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.domain.FixerProfile;
import com.fixup.fixers.domain.FixerProfiles;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

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
}
