package com.fixup.media.domain;

import com.fixup.media.api.PortfolioRuleException;

/** FR-UC-17: publication rules of the portfolio live here, not in the controller. */
public final class PortfolioPolicy {
    /** A portfolio is a curated sample, not a file dump. */
    public static final int MAX_PIECES = 20;
    public static final int TITLE_MAX = 120;
    public static final int DESCRIPTION_MAX = 1000;
    public static final int STORAGE_KEY_MAX = 512;

    private PortfolioPolicy() {
    }

    public static void requireRoomFor(int currentPieces) {
        if (currentPieces >= MAX_PIECES) {
            throw new PortfolioRuleException("PORTFOLIO_FULL",
                    "The portfolio already holds the maximum of " + MAX_PIECES + " pieces");
        }
    }

    /** Pieces are appended, so the gallery keeps the order the fixer published them in. */
    public static int nextPosition(int highestPosition) {
        return highestPosition + 1;
    }
}
