package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum ChargeStatus {
    PENDING,
    APPROVED,
    DECLINED
}
