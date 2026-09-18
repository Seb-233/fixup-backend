package com.fixup.quotations.api;

/** The requested transition is not valid for the current state of the quotation. */
public class QuotationConflictException extends RuntimeException {
    private final String code;

    public QuotationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
