package com.fixup.requests.api;

import java.util.UUID;

public record SlaWarningDue(
        UUID requestId,
        UUID ownerUserId,
        UUID fixerUserId,
        String title,
        String message) {
}
