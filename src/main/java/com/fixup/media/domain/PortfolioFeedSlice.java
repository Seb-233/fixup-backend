package com.fixup.media.domain;

import java.util.List;

/** One page of the public ideas feed and whether another page follows. */
public record PortfolioFeedSlice(List<PortfolioPiece> pieces, boolean hasMore) {
    public PortfolioFeedSlice {
        pieces = List.copyOf(pieces);
    }
}
