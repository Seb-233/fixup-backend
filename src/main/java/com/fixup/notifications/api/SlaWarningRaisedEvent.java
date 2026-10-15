package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from requests module when ready. */
public record SlaWarningRaisedEvent(
        UUID requestId,
        UUID ownerUserId,
        UUID platformAdminUserId,
        Instant warningAt) {
}
