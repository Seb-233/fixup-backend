package com.fixup.payments.api;

/** The payment does not exist, or row-level security makes it invisible to the caller. */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException() {
        super("The payment does not exist");
    }
}
