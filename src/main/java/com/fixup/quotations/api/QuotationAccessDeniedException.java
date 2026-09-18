package com.fixup.quotations.api;

/** The actor is neither the fixer who quoted nor the owner who received the quotation. */
public class QuotationAccessDeniedException extends RuntimeException {
    public QuotationAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}
