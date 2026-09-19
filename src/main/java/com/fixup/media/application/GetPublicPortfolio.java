package com.fixup.media.application;

import com.fixup.media.api.PortfolioNotFoundException;
import com.fixup.media.domain.FixerPortfolios;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: read the public portfolio of a fixer. Returns only published portfolios;
 * returns 404 if draft or non-existent.
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
    public List<PortfolioPieceView> execute(UUID fixerUserId) {
        var portfolio = fixerPortfolios.findById(fixerUserId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found or not published"));
        if (!portfolio.isPublished()) {
            throw new PortfolioNotFoundException("Portfolio not found or not published");
        }
        return resolver.toViews(pieces.findPublicOfFixer(fixerUserId));
    }
}
