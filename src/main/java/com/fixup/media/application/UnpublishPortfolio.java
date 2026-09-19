package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.domain.FixerPortfolios;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnpublishPortfolio {
    private final FixerPortfolios fixerPortfolios;
    private final FixerEligibility eligibility;

    public UnpublishPortfolio(FixerPortfolios fixerPortfolios, FixerEligibility eligibility) {
        this.fixerPortfolios = fixerPortfolios;
        this.eligibility = eligibility;
    }

    @Transactional
    public PortfolioStatusResponse execute(CurrentActor actor) {
        eligibility.requireVerified(actor);

        var portfolio = fixerPortfolios.findOrCreateForUpdate(actor.internalUserId());
        var unpublished = portfolio.unpublish(Instant.now());
        fixerPortfolios.save(unpublished);

        return new PortfolioStatusResponse(unpublished.fixerUserId(), unpublished.status().name(), unpublished.publishedAt());
    }
}
