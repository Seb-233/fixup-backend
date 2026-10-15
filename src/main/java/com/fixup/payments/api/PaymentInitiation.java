package com.fixup.payments.api;

/**
 * Starts the financial lifecycle after a quotation is accepted.
 *
 * <p>The future implementation owns its own persistence and must not read quotation or request
 * repositories. {@code paymentMethod} is the canonical {@code PaymentMethod.name()} value
 * ({@code TRANSFER} or {@code CASH}); it is a string here deliberately so that payments remains
 * independent from requests.
 */
public interface PaymentInitiation {

    void initiate(PaymentInitiationCommand command);
}
