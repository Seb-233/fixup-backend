package com.fixup.contracts.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum PaymentFrequency {
    MONTHLY,
    BIWEEKLY,
    WEEKLY,
    QUARTERLY,
    YEARLY
}
