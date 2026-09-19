package com.fixup.payments.api;

/** The requested money movement does not apply to the current state of the account. */
public class PaymentConflictException extends RuntimeException {
    private final String code;

    public PaymentConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
