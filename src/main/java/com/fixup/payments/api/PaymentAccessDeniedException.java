package com.fixup.payments.api;

/** Money is read and moved only by the fixer who earned it. */
public class PaymentAccessDeniedException extends RuntimeException {
    public PaymentAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}
