package com.fixup.payments.api;

/** Capture accepted repair context in the acceptance transaction, including historical CASH. */
public interface PaymentContextRegistration {
    void capture(PaymentContext context);
}
