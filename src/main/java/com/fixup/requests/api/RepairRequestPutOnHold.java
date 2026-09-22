package com.fixup.requests.api;

import java.time.Instant;
import java.util.UUID;

public record RepairRequestPutOnHold(
        UUID requestId,
        UUID ownerUserId,
        UUID fixerUserId,
        Instant at) {
}
