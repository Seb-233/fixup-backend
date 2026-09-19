package com.fixup.media.domain;

import java.util.Optional;
import java.util.UUID;

public interface FixerPortfolios {
    void save(FixerPortfolio portfolio);

    Optional<FixerPortfolio> findById(UUID fixerUserId);

    FixerPortfolio findOrCreateForUpdate(UUID fixerUserId);
}
