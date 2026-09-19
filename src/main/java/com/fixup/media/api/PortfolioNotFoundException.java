package com.fixup.media.api;

public class PortfolioNotFoundException extends MediaException {
    public PortfolioNotFoundException(String message) {
        super(404, "PORTFOLIO_NOT_FOUND", message);
    }
}
