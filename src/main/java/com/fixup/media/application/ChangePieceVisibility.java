package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.PieceNotFoundException;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangePieceVisibility {
    private final PortfolioPieces pieces;
    private final MediaAssets mediaAssets;
    private final FixerPortfolios fixerPortfolios;
    private final FixerEligibility eligibility;
    private final Clock clock;

    public ChangePieceVisibility(
            PortfolioPieces pieces,
            MediaAssets mediaAssets,
            FixerPortfolios fixerPortfolios,
            FixerEligibility eligibility,
            Clock clock) {
        this.pieces = pieces;
        this.mediaAssets = mediaAssets;
        this.fixerPortfolios = fixerPortfolios;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    @Transactional
    public PortfolioPiece hide(CurrentActor actor, UUID pieceId) {
        eligibility.requireVerified(actor);
        var portfolio = fixerPortfolios.findOrCreateForUpdate(actor.internalUserId());

        var piece = pieces.findById(pieceId)
                .orElseThrow(() -> new PieceNotFoundException("There is no such piece in this portfolio"));
        piece.requireOwnedBy(actor.internalUserId());

        Instant now = Instant.now(clock);
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
                .orElseThrow(() -> new PieceNotFoundException("There is no such piece in this portfolio"));
        piece.requireOwnedBy(actor.internalUserId());

        var shown = piece.show(Instant.now(clock));
        pieces.save(shown);
        return shown;
    }
}
