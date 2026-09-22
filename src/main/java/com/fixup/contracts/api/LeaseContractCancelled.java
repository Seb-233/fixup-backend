package com.fixup.contracts.api;

import java.time.Instant;
import java.util.UUID;

public record LeaseContractCancelled(
        UUID contractId,
        UUID ownerUserId,
        UUID tenantUserId,
        UUID cancelledBy,
        String reason,
        Instant at) {
}
