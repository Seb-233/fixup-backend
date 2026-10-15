package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from contracts module when ready. */
public record ContractCreatedEvent(
        UUID contractId,
        UUID ownerUserId,
        UUID tenantUserId,
        Instant createdAt) {
}
