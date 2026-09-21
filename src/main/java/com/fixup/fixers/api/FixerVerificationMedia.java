package com.fixup.fixers.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the fixers module needs from media for identity verification documents: validating that an
 * uploaded document really belongs to the fixer, has the right purpose and is ready, and resolving
 * signed read URLs for the ones already filed.
 *
 * <p>This lives here, rather than fixers depending on media.api directly, because media already
 * depends on fixers.api (FixerEligibility) for its own eligibility checks; a module dependency in
 * the other direction would create a cycle. Implemented inside the media module instead, which is
 * dependency inversion at the module boundary, not a workaround.
 */
public interface FixerVerificationMedia {

    /**
     * Verifies that the media IDs belong to ownerUserId, have purpose FIXER_VERIFICATION, are in
     * status READY, and marks them ATTACHED in the same transaction.
     */
    void attachDocuments(UUID ownerUserId, List<UUID> mediaIds);

    /** Resolves signed GET read URLs with expiration for the given media IDs. */
    List<SignedDocument> resolveReadUrls(List<UUID> mediaIds);

    record SignedDocument(UUID mediaId, String readUrl, Instant readUrlExpiresAt) {
    }
}
