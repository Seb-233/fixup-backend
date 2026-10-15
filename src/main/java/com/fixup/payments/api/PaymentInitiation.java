package com.fixup.payments.api;

/**
 * Starts the financial lifecycle for an accepted TRANSFER quotation.
 *
 * <p>The implementation owns its persistence and does not read quotation or request
 * repositories. {@code paymentMethod} is the canonical {@code PaymentMethod.name()} value
 * ({@code TRANSFER} or {@code CASH}); it is a string here deliberately so that payments remains
 * independent from requests.
 */
public interface PaymentInitiation {

    PaymentInitiationResult initiate(PaymentInitiationCommand command);
}
