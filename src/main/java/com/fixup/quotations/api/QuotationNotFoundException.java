package com.fixup.quotations.api;

/** The quotation does not exist, or the caller is not entitled to learn that it does. */
public class QuotationNotFoundException extends RuntimeException {
    public QuotationNotFoundException() {
        super("The quotation does not exist");
    }
}
