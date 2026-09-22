package com.fixup.requests.api;

import java.time.Instant;
import java.util.UUID;

public record RepairRequestUrgencyChanged(
        UUID requestId,
        UUID ownerUserId,
        UUID fixerUserId,
        RepairRequestUrgency previousUrgency,
        RepairRequestUrgency newUrgency,
        Instant at) {
}
