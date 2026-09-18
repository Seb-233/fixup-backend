package com.fixup.fixers.infrastructure;

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
    public void create(FixerProfile profile) {
        repository.saveAndFlush(FixerProfileEntity.from(profile));
    }
}
