package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner sees the whole portfolio, hidden pieces included. */
@Service
public class ListOwnPortfolio {
    private final PortfolioPieces pieces;
    private final FixerEligibility eligibility;

    ListOwnPortfolio(PortfolioPieces pieces, FixerEligibility eligibility) {
        this.pieces = pieces;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public List<PortfolioPiece> execute(CurrentActor actor) {
        eligibility.requireVerified(actor);
        return pieces.findAllOfFixer(actor.internalUserId());
    }
}
