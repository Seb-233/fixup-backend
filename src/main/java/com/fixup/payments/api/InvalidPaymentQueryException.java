package com.fixup.payments.api;

/** A requested payment-history page or filter is outside the public contract. */
public class InvalidPaymentQueryException extends RuntimeException {
    public InvalidPaymentQueryException(String message) {
        super(message);
    }
}
