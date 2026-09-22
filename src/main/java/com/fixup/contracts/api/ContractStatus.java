package com.fixup.contracts.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum ContractStatus {
    DRAFT,
    PENDING_TENANT_SIGNATURE,
    PENDING_OWNER_SIGNATURE,
    SIGNED,
    ACTIVE,
    EXPIRED,
    TERMINATED_BY_OWNER,
    TERMINATED_BY_TENANT,
    CANCELLED
}
