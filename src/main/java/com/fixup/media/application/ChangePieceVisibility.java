package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.PortfolioRuleException;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-17: the fixer curates which pieces the public portfolio shows. */
@Service
public class ChangePieceVisibility {
    private final PortfolioPieces pieces;
    private final FixerEligibility eligibility;

    ChangePieceVisibility(PortfolioPieces pieces, FixerEligibility eligibility) {
        this.pieces = pieces;
        this.eligibility = eligibility;
    }

    @Transactional
    public PortfolioPiece hide(CurrentActor actor, UUID pieceId) {
        return change(actor, pieceId, PortfolioPiece::hide);
    }

    @Transactional
    public PortfolioPiece show(CurrentActor actor, UUID pieceId) {
        return change(actor, pieceId, PortfolioPiece::show);
    }

    private PortfolioPiece change(CurrentActor actor, UUID pieceId,
            BiFunction<PortfolioPiece, Instant, PortfolioPiece> transition) {
        eligibility.requireVerified(actor);
        var piece = pieces.findById(pieceId)
                .orElseThrow(() -> new PortfolioRuleException("PIECE_NOT_FOUND",
                        "There is no such piece in this portfolio"));
        // A missing piece and someone else's piece answer the same way: no portfolio is enumerable.
        piece.requireOwnedBy(actor.internalUserId());
        var updated = transition.apply(piece, Instant.now());
        pieces.save(updated);
        return updated;
    }
}
