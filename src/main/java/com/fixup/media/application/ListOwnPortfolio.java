package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.PortfolioPieces;
import com.fixup.media.domain.PortfolioStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner sees the whole portfolio, hidden pieces included, with secure read URLs. */
@Service
public class ListOwnPortfolio {
    private final PortfolioPieces pieces;
    private final FixerPortfolios fixerPortfolios;
    private final PortfolioViewResolver resolver;
    private final FixerEligibility eligibility;

    ListOwnPortfolio(
            PortfolioPieces pieces,
            FixerPortfolios fixerPortfolios,
            PortfolioViewResolver resolver,
            FixerEligibility eligibility) {
        this.pieces = pieces;
        this.fixerPortfolios = fixerPortfolios;
        this.resolver = resolver;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public OwnPortfolioView execute(CurrentActor actor) {
        eligibility.requireVerified(actor);
        var fixerUserId = actor.internalUserId();
        var portfolioOpt = fixerPortfolios.findById(fixerUserId);
        var status = portfolioOpt.map(p -> p.status().name()).orElse(PortfolioStatus.DRAFT.name());
        var publishedAt = portfolioOpt.map(p -> p.publishedAt()).orElse(null);
        var pieceViews = resolver.toViews(pieces.findAllOfFixer(fixerUserId));
        return new OwnPortfolioView(fixerUserId, status, publishedAt, pieceViews);
    }
}
