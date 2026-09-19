package com.fixup.media.infrastructure;

import com.fixup.media.domain.FixerPortfolio;
import com.fixup.media.domain.FixerPortfolios;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaFixerPortfolios implements FixerPortfolios {
    private final FixerPortfolioJpaRepository repository;

    JpaFixerPortfolios(FixerPortfolioJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(FixerPortfolio portfolio) {
        var entity = repository.findById(portfolio.fixerUserId())
                .orElseGet(() -> FixerPortfolioEntity.from(portfolio));
        entity.apply(portfolio);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<FixerPortfolio> findById(UUID fixerUserId) {
        return repository.findById(fixerUserId).map(FixerPortfolioEntity::toDomain);
    }

    @Override
    public FixerPortfolio findOrCreateForUpdate(UUID fixerUserId) {
        var existing = repository.lockByFixerUserId(fixerUserId);
        if (existing.isPresent()) {
            return existing.get().toDomain();
        }
        var initial = FixerPortfolio.initialDraft(fixerUserId, Instant.now());
        try {
            repository.saveAndFlush(FixerPortfolioEntity.from(initial));
        } catch (DataIntegrityViolationException conflict) {
            // Another thread inserted concurrently; lock it now.
        }
        return repository.lockByFixerUserId(fixerUserId)
                .map(FixerPortfolioEntity::toDomain)
                .orElse(initial);
    }
}
