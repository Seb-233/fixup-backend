package com.fixup.fixers.api;

import java.util.UUID;

/** Internal event, published in the review transaction. Never contains a JWT or a storage key. */
public record FixerVerificationDecided(UUID internalUserId, FixerVerificationStatus status) {
}
