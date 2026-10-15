package com.fixup.contracts.api;

import java.util.UUID;

public record ContractCreated(
    UUID contractId,
    UUID propertyId,
    UUID ownerUserId,
    UUID tenantUserId
) {}
