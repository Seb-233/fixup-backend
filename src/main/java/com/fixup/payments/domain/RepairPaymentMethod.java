package com.fixup.payments.domain;

/** Internal, canonical representation of the request payment-method name. */
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
