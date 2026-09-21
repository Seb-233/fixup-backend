package com.fixup.media.api;

public class PortfolioInsufficientPiecesException extends MediaException {
    public PortfolioInsufficientPiecesException(String message) {
        super(409, "PORTFOLIO_INSUFFICIENT_PIECES", message);
    }
}
