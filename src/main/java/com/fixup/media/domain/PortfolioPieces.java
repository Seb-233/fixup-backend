package com.fixup.media.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port owned by media. No other module reaches this repository. */
public interface PortfolioPieces {
    void save(PortfolioPiece piece);

    Optional<PortfolioPiece> findById(UUID pieceId);

    /** Every piece of the fixer, hidden ones included, in publication order. */
    List<PortfolioPiece> findAllOfFixer(UUID fixerUserId);

    /** Only the pieces the fixer chose to show, in publication order. */
    List<PortfolioPiece> findPublicOfFixer(UUID fixerUserId);
}
