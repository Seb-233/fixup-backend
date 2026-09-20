package com.fixup.contracts.api;

import java.time.Instant;
import java.util.UUID;

public record LeaseContractCreated(
        UUID contractId,
        UUID propertyId,
        UUID ownerUserId,
        UUID tenantUserId,
        UUID managerUserId,
        long monthlyRent,
        Instant createdAt) {
}
