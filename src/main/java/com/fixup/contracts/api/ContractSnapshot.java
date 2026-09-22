package com.fixup.contracts.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ContractSnapshot(
        UUID contractId,
        UUID propertyId,
        UUID ownerUserId,
        UUID tenantUserId,
        UUID realEstateManagerUserId,
        ContractStatus status,
        long monthlyRent,
        LocalDate startDate,
        LocalDate endDate,
        Instant createdAt) {

    public void requireVisibleBy(UUID userId) {
        if (!ownerUserId.equals(userId) && !tenantUserId.equals(userId)
                && !(realEstateManagerUserId != null && realEstateManagerUserId.equals(userId))) {
            throw new ContractAccessDeniedException();
        }
    }
}
