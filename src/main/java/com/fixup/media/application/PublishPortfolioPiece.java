package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import com.fixup.media.domain.PortfolioPolicy;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: only a verified fixer publishes. Eligibility is asked to the fixers module through
 * its api contract; media never reads fixer tables.
 *
 * <p>Counting the pieces, taking the highest position and inserting the next one is a
 * read-modify-write over the whole portfolio, so the read holds a write lock until the
 * transaction ends. Two simultaneous publications are therefore serialized instead of counting
 * the same pieces, deriving the same position and colliding on the unique (fixer, position)
 * constraint. If a writer still loses the race &mdash; a first publication of an empty portfolio
 * has no row to lock &mdash; the infrastructure reports it as a portfolio rule, so the API answers
 * 409 and never 500, and the cap of {@link PortfolioPolicy#MAX_PIECES} pieces cannot be exceeded.
 */
@Service
public class PublishPortfolioPiece {
    private final PortfolioPieces pieces;
    private final FixerEligibility eligibility;

    PublishPortfolioPiece(PortfolioPieces pieces, FixerEligibility eligibility) {
        this.pieces = pieces;
        this.eligibility = eligibility;
    }

    @Transactional
    public PortfolioPiece execute(CurrentActor actor, NewPortfolioPiece request) {
        eligibility.requireVerified(actor);
        var current = pieces.findAllOfFixerForUpdate(actor.internalUserId());
        PortfolioPolicy.requireRoomFor(current.size());
        var highest = current.stream().mapToInt(PortfolioPiece::position).max().orElse(0);
        var piece = PortfolioPiece.publish(actor.internalUserId(), request.kind(), request.storageKey(),
                request.title(), request.description(), PortfolioPolicy.nextPosition(highest), Instant.now());
        pieces.save(piece);
        return piece;
    }
}
