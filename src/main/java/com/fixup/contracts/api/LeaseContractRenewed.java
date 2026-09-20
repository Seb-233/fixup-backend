package com.fixup.contracts.api;

import java.time.Instant;
import java.util.UUID;

public record LeaseContractRenewed(
        UUID newContractId,
        UUID previousContractId,
        UUID ownerUserId,
        UUID tenantUserId,
        Instant at) {
}
