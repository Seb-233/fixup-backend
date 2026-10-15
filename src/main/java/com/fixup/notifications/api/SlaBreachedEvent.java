package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from requests module when ready. */
public record SlaBreachedEvent(
        UUID requestId,
        UUID ownerUserId,
        UUID platformAdminUserId,
        Instant breachedAt) {
}
