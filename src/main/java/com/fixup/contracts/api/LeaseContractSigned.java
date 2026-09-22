package com.fixup.contracts.api;

import java.time.Instant;
import java.util.UUID;

public record LeaseContractSigned(
        UUID contractId,
        UUID ownerUserId,
        UUID tenantUserId,
        UUID signedBy,
        Instant at) {
}
