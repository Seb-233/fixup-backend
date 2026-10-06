package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from contracts module when ready. */
public record ContractExpiringSoonEvent(
        UUID contractId,
        UUID ownerUserId,
        UUID tenantUserId,
        int daysUntilExpiry,
        Instant expiresAt) {
}
