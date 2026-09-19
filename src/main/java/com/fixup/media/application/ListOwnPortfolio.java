package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner sees the whole portfolio, hidden pieces included, with secure read URLs. */
@Service
public class ListOwnPortfolio {
    private final PortfolioPieces pieces;
    private final PortfolioViewResolver resolver;
    private final FixerEligibility eligibility;

    ListOwnPortfolio(PortfolioPieces pieces, PortfolioViewResolver resolver, FixerEligibility eligibility) {
        this.pieces = pieces;
        this.resolver = resolver;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public List<PortfolioPieceView> execute(CurrentActor actor) {
        eligibility.requireVerified(actor);
        return resolver.toViews(pieces.findAllOfFixer(actor.internalUserId()));
    }
}
