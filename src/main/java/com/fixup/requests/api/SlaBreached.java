package com.fixup.requests.api;

import java.util.UUID;

public record SlaBreached(
        UUID requestId,
        UUID propertyId,
        UUID ownerUserId,
        long remainingMinutes) {
}
