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
        var current = pieces.findAllOfFixer(actor.internalUserId());
        PortfolioPolicy.requireRoomFor(current.size());
        var highest = current.stream().mapToInt(PortfolioPiece::position).max().orElse(0);
        var piece = PortfolioPiece.publish(actor.internalUserId(), request.kind(), request.storageKey(),
                request.title(), request.description(), PortfolioPolicy.nextPosition(highest), Instant.now());
        pieces.save(piece);
        return piece;
    }
}
