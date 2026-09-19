package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.PortfolioPieces;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishPortfolio {
    private final FixerPortfolios fixerPortfolios;
    private final PortfolioPieces pieces;
    private final MediaAssets mediaAssets;
    private final FixerEligibility eligibility;

    public PublishPortfolio(
            FixerPortfolios fixerPortfolios,
            PortfolioPieces pieces,
            MediaAssets mediaAssets,
            FixerEligibility eligibility) {
        this.fixerPortfolios = fixerPortfolios;
        this.pieces = pieces;
        this.mediaAssets = mediaAssets;
        this.eligibility = eligibility;
    }

    @Transactional
    public PortfolioStatusResponse execute(CurrentActor actor) {
        eligibility.requireVerified(actor);

        var portfolio = fixerPortfolios.findOrCreateForUpdate(actor.internalUserId());
        var publicPieces = pieces.findPublicOfFixer(actor.internalUserId());

        int count = (int) publicPieces.stream()
                .filter(p -> mediaAssets.findById(p.mediaId())
                        .map(a -> a.status() == MediaAssetStatus.ATTACHED)
                        .orElse(false))
                .count();

        Instant now = Instant.now();
        var published = portfolio.publish(count, now);
        fixerPortfolios.save(published);

        return new PortfolioStatusResponse(published.fixerUserId(), published.status().name(), published.publishedAt());
    }
}
