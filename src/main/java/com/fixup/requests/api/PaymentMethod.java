package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Payment agreement selected when a repair request is opened.
 *
 * <p>The method belongs to the request because it is agreed before a financial transaction
 * exists. Financial contracts consume its stable enum name so that payments does not depend on
 * the requests module.
 */
@Schema(enumAsRef = true)
public enum PaymentMethod {
    TRANSFER,
    CASH;

    /** Only for creation commands; never normalize a persisted historical request. */
    public static PaymentMethod requireDigitalCreation(PaymentMethod method) {
        java.util.Objects.requireNonNull(method, "paymentMethod must not be null");
        if (method != TRANSFER) throw new RepairRequestConflictException("DIGITAL_PAYMENT_REQUIRED", "New repairs require digital payment");
        return method;
    }
}
