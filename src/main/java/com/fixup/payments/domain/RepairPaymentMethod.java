package com.fixup.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** Internal, canonical representation of the request payment-method name. */
@Schema(name = "PaymentMethod", enumAsRef = true)
public enum RepairPaymentMethod {
    TRANSFER,
    CASH;

    public static RepairPaymentMethod fromCanonical(String value) {
        if (value == null) {
            throw new IllegalArgumentException("paymentMethod must be TRANSFER or CASH");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("paymentMethod must be TRANSFER or CASH");
        }
    }
}
