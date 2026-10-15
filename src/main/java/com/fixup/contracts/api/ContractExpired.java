package com.fixup.contracts.api;

import java.util.UUID;

public record ContractExpired(
    UUID contractId,
    UUID propertyId,
    UUID ownerUserId,
    UUID tenantUserId
) {}
