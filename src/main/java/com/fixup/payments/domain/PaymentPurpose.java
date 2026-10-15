package com.fixup.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum PaymentPurpose {
    SERVICE,
    OUTSTANDING_BALANCE
}
