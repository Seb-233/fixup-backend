package com.fixup.media.api;

/** A publication rule of the portfolio rejected the operation. Carries a stable client code. */
public class PortfolioRuleException extends RuntimeException {
    private final String code;

    public PortfolioRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
