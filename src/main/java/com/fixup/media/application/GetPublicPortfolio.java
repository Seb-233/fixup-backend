package com.fixup.media.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.media.api.PortfolioNotFoundException;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: read the public portfolio of a fixer. Returns only published portfolios;
 * returns 404 if draft or non-existent. Requires an active reader account.
 */
@Service
public class GetPublicPortfolio {
    private final PortfolioPieces pieces;
    private final FixerPortfolios fixerPortfolios;
    private final PortfolioViewResolver resolver;

    GetPublicPortfolio(PortfolioPieces pieces, FixerPortfolios fixerPortfolios, PortfolioViewResolver resolver) {
        this.pieces = pieces;
        this.fixerPortfolios = fixerPortfolios;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public List<PortfolioPieceView> execute(CurrentActor reader, UUID fixerUserId) {
        if (reader == null || reader.status() != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Account is not active or operation is not allowed");
        }
        var portfolio = fixerPortfolios.findById(fixerUserId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found or not published"));
        if (!portfolio.isPublished()) {
            throw new PortfolioNotFoundException("Portfolio not found or not published");
        }
        return resolver.toViews(pieces.findPublicOfFixer(fixerUserId));
    }
}
