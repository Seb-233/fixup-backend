package com.fixup.payments.api;

/** The current actor is not allowed to read or mutate the requested financial resource. */
public class PaymentAccessDeniedException extends RuntimeException {
    public PaymentAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}
