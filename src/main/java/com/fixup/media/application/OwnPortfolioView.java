package com.fixup.media.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OwnPortfolioView(
        UUID fixerUserId,
        String status,
        Instant publishedAt,
        List<PortfolioPieceView> pieces) {
    public OwnPortfolioView {
        pieces = pieces == null ? List.of() : List.copyOf(pieces);
    }
}
