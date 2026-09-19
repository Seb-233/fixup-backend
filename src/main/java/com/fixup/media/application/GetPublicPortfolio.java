package com.fixup.media.application;

import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: read the public portfolio of a fixer with secure presigned read URLs.
 */
@Service
public class GetPublicPortfolio {
    private final PortfolioPieces pieces;
    private final PortfolioViewResolver resolver;

    GetPublicPortfolio(PortfolioPieces pieces, PortfolioViewResolver resolver) {
        this.pieces = pieces;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public List<PortfolioPieceView> execute(UUID fixerUserId) {
        return resolver.toViews(pieces.findPublicOfFixer(fixerUserId));
    }
}
