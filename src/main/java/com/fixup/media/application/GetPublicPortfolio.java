package com.fixup.media.application;

import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: any authenticated user may read the public portfolio of a fixer. Hidden pieces never
 * leave this use case, so visibility is enforced in the query and not by the caller.
 */
@Service
public class GetPublicPortfolio {
    private final PortfolioPieces pieces;

    GetPublicPortfolio(PortfolioPieces pieces) {
        this.pieces = pieces;
    }

    @Transactional(readOnly = true)
    public List<PortfolioPiece> execute(UUID fixerUserId) {
        return pieces.findPublicOfFixer(fixerUserId);
    }
}
