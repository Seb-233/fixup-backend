package com.fixup.payments.api;

/** INT-01: la pasarela externa no respondió o no está configurada; el estado del pago no cambió. */
public class PaymentGatewayUnavailableException extends RuntimeException {
    private final String code;

    public PaymentGatewayUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PaymentGatewayUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
