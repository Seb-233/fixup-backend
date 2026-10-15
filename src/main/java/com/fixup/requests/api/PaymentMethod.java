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
    CASH
}
