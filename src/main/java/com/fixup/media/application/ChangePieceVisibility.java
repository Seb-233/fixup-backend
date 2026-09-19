package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.PortfolioRuleException;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-17: the fixer curates which pieces the public portfolio shows. */
@Service
public class ChangePieceVisibility {
    private final PortfolioPieces pieces;
    private final MediaAssets mediaAssets;
    private final FixerPortfolios fixerPortfolios;
    private final FixerEligibility eligibility;

    ChangePieceVisibility(
            PortfolioPieces pieces,
            MediaAssets mediaAssets,
            FixerPortfolios fixerPortfolios,
            FixerEligibility eligibility) {
        this.pieces = pieces;
        this.mediaAssets = mediaAssets;
        this.fixerPortfolios = fixerPortfolios;
        this.eligibility = eligibility;
    }

    @Transactional
    public PortfolioPiece hide(CurrentActor actor, UUID pieceId) {
        eligibility.requireVerified(actor);
        var portfolio = fixerPortfolios.findOrCreateForUpdate(actor.internalUserId());

        var piece = pieces.findById(pieceId)
                .orElseThrow(() -> new PortfolioRuleException("PIECE_NOT_FOUND", "There is no such piece in this portfolio"));
        piece.requireOwnedBy(actor.internalUserId());

        Instant now = Instant.now();
        var hidden = piece.hide(now);
        pieces.save(hidden);

        var publicPieces = pieces.findPublicOfFixer(actor.internalUserId());
        int remainingCount = (int) publicPieces.stream()
                .filter(p -> !p.id().equals(pieceId))
                .filter(p -> mediaAssets.findById(p.mediaId())
                        .map(a -> a.status() == MediaAssetStatus.ATTACHED)
                        .orElse(false))
                .count();

        var updatedPortfolio = portfolio.revertToDraftIfInsufficient(remainingCount, now);
        fixerPortfolios.save(updatedPortfolio);

        return hidden;
    }

    @Transactional
    public PortfolioPiece show(CurrentActor actor, UUID pieceId) {
        eligibility.requireVerified(actor);
        fixerPortfolios.findOrCreateForUpdate(actor.internalUserId());

        var piece = pieces.findById(pieceId)
                .orElseThrow(() -> new PortfolioRuleException("PIECE_NOT_FOUND", "There is no such piece in this portfolio"));
        piece.requireOwnedBy(actor.internalUserId());

        var shown = piece.show(Instant.now());
        pieces.save(shown);
        return shown;
    }
}
