package com.fixup.contracts.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum ContractStatus {
    DRAFT, ACTIVE, EXPIRED, TERMINATED, RENEWED
}
