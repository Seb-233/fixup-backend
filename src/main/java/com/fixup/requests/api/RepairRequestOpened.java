package com.fixup.requests.api;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestUrgency;
import java.time.Instant;
import java.util.UUID;

public record RepairRequestOpened(
        UUID requestId,
        UUID ownerUserId,
        Specialty specialty,
        String title,
        RepairRequestUrgency urgency,
        Instant createdAt) {
}
