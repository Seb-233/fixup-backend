package com.fixup.media.api;

import java.util.List;
import java.util.UUID;

/**
 * Public contract exposed by the media module for repair requests to attach photos
 * and resolve presigned GET URLs without accessing internal media entities or storage keys.
 */
public interface MediaAttachmentService {

    /**
     * Verifies that the media IDs belong to ownerUserId, have purpose REPAIR_REQUEST,
     * are in status READY, and marks them ATTACHED in the same transaction.
     */
    void attachRepairRequestPhotos(UUID ownerUserId, List<UUID> mediaIds);

    /**
     * Verifies that the media IDs belong to ownerUserId, have purpose FIXER_VERIFICATION,
     * are in status READY, and marks them ATTACHED in the same transaction.
     */
    void attachFixerVerificationDocuments(UUID ownerUserId, List<UUID> mediaIds);

    /**
     * Resolves signed GET read URLs with expiration for the given media IDs.
     */
    List<SignedMediaView> resolveReadUrls(List<UUID> mediaIds);
}
