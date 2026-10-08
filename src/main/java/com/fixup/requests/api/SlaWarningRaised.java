package com.fixup.requests.api;

import java.util.UUID;

public record SlaWarningRaised(
        UUID requestId,
        UUID propertyId,
        UUID ownerUserId,
        long remainingMinutes) {
}
