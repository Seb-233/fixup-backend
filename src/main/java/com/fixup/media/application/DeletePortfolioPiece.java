package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.PieceNotFoundException;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.PortfolioPieces;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletePortfolioPiece {
    private final PortfolioPieces pieces;
    private final MediaAssets mediaAssets;
    private final FixerPortfolios fixerPortfolios;
    private final MediaDeletionService deletionService;
    private final FixerEligibility eligibility;

    public DeletePortfolioPiece(
            PortfolioPieces pieces,
            MediaAssets mediaAssets,
            FixerPortfolios fixerPortfolios,
            MediaDeletionService deletionService,
            FixerEligibility eligibility) {
        this.pieces = pieces;
        this.mediaAssets = mediaAssets;
        this.fixerPortfolios = fixerPortfolios;
        this.deletionService = deletionService;
        this.eligibility = eligibility;
    }

    @Transactional
    public void execute(CurrentActor actor, UUID pieceId) {
        eligibility.requireVerified(actor);

        var portfolio = fixerPortfolios.findOrCreateForUpdate(actor.internalUserId());

        var piece = pieces.findById(pieceId)
                .orElseThrow(() -> new PieceNotFoundException("There is no such piece in this portfolio"));
        piece.requireOwnedBy(actor.internalUserId());

        mediaAssets.findByIdForUpdate(piece.mediaId()).ifPresent(asset -> {
            var marked = asset.markDeletionPending();
            mediaAssets.save(marked);
            deletionService.scheduleDeletion(marked.id(), marked.objectKey());
        });

        pieces.delete(pieceId);

        var publicPieces = pieces.findPublicOfFixer(actor.internalUserId());
        int remainingCount = (int) publicPieces.stream()
                .filter(p -> !p.id().equals(pieceId))
                .filter(p -> mediaAssets.findById(p.mediaId())
                        .map(a -> a.status() == MediaAssetStatus.ATTACHED)
                        .orElse(false))
                .count();

        Instant now = Instant.now();
        var updatedPortfolio = portfolio.revertToDraftIfInsufficient(remainingCount, now);
        fixerPortfolios.save(updatedPortfolio);
    }
}
