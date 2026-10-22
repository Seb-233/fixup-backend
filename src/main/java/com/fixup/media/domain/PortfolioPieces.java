package com.fixup.media.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port owned by media. No other module reaches this repository. */
public interface PortfolioPieces {
    void save(PortfolioPiece piece);

    Optional<PortfolioPiece> findById(UUID pieceId);

    /** Every piece of the fixer, hidden ones included, in publication order. */
    List<PortfolioPiece> findAllOfFixer(UUID fixerUserId);

    /**
     * The same read, holding a write lock on the portfolio until the transaction ends. Publication
     * counts the pieces and derives the next position from them, so two concurrent publications
     * must not observe the same portfolio: without the lock both count the same pieces, take the
     * same highest position and fight over the unique (fixer, position) constraint.
     */
    List<PortfolioPiece> findAllOfFixerForUpdate(UUID fixerUserId);

    /** Only the pieces the fixer chose to show, in publication order. */
    List<PortfolioPiece> findPublicOfFixer(UUID fixerUserId);

    /**
     * One page of the public pieces of every published portfolio, for the ideas feed. The order
     * interleaves fixers (every first piece, then every second one) and is stable across pages.
     * A null filter reads every fixer; an empty one reads nobody.
     */
    PortfolioFeedSlice findPublishedFeed(Collection<UUID> fixerFilter, int page, int size);

    void delete(UUID pieceId);
}
