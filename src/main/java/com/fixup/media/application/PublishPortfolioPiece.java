package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaAlreadyAttachedException;
import com.fixup.media.api.MediaInvalidException;
import com.fixup.media.api.MediaNotFoundException;
import com.fixup.media.api.MediaNotReadyException;
import com.fixup.media.api.UploadExpiredException;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import com.fixup.media.domain.PortfolioPolicy;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: only a verified fixer publishes a piece. Attaches an owned and confirmed MediaAsset.
 */
@Service
public class PublishPortfolioPiece {
    private static final String REQUIRED_PURPOSE = "FIXER_PORTFOLIO";

    private final PortfolioPieces pieces;
    private final MediaAssets mediaAssets;
    private final FixerEligibility eligibility;

    PublishPortfolioPiece(PortfolioPieces pieces, MediaAssets mediaAssets, FixerEligibility eligibility) {
        this.pieces = pieces;
        this.mediaAssets = mediaAssets;
        this.eligibility = eligibility;
    }

    @Transactional
    public PortfolioPiece execute(CurrentActor actor, NewPortfolioPiece request) {
        eligibility.requireVerified(actor);

        MediaAsset asset = mediaAssets.findByIdForUpdate(request.mediaId())
                .orElseThrow(() -> new MediaNotFoundException("There is no such media for this user"));
        asset.requireBelongsTo(actor.internalUserId());

        if (!REQUIRED_PURPOSE.equals(asset.purpose())) {
            throw new MediaNotFoundException("Media not found for portfolio");
        }
        if (asset.status() == MediaAssetStatus.PENDING) {
            throw new MediaNotReadyException("Media is not ready yet; confirm upload first");
        }
        if (asset.status() == MediaAssetStatus.ATTACHED) {
            throw new MediaAlreadyAttachedException("Media asset is already attached to a portfolio piece");
        }
        if (asset.status() == MediaAssetStatus.EXPIRED) {
            throw new UploadExpiredException("Media upload ticket has expired");
        }
        if (asset.status() == MediaAssetStatus.INVALID) {
            throw new MediaInvalidException("Media asset was marked invalid");
        }
        if (asset.status() == MediaAssetStatus.DELETED) {
            throw new MediaNotFoundException("Media asset was deleted");
        }

        var current = pieces.findAllOfFixerForUpdate(actor.internalUserId());
        PortfolioPolicy.requireRoomFor(current.size());
        var highest = current.stream().mapToInt(PortfolioPiece::position).max().orElse(0);

        var piece = PortfolioPiece.publish(
                actor.internalUserId(),
                request.mediaId(),
                request.title(),
                request.description(),
                PortfolioPolicy.nextPosition(highest),
                Instant.now());

        pieces.save(piece);
        mediaAssets.save(asset.markAttached());
        return piece;
    }
}
