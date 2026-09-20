package com.fixup.contracts.api;

import java.time.Instant;
import java.util.UUID;

public record LeaseContractTerminated(
        UUID contractId,
        UUID ownerUserId,
        UUID tenantUserId,
        UUID terminatedBy,
        String reason,
        Instant at) {
}
