package com.fixup.media.infrastructure;

import com.fixup.fixers.api.FixerVerificationMedia;
import com.fixup.media.api.MediaAttachmentService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Exposes MediaAttachmentService to the fixers module through its own FixerVerificationMedia port. */
@Component
class MediaFixerVerificationAdapter implements FixerVerificationMedia {
    private final MediaAttachmentService mediaAttachmentService;

    MediaFixerVerificationAdapter(MediaAttachmentService mediaAttachmentService) {
        this.mediaAttachmentService = mediaAttachmentService;
    }

    @Override
    public void attachDocuments(UUID ownerUserId, List<UUID> mediaIds) {
        mediaAttachmentService.attachFixerVerificationDocuments(ownerUserId, mediaIds);
    }

    @Override
    public List<SignedDocument> resolveReadUrls(List<UUID> mediaIds) {
        return mediaAttachmentService.resolveReadUrls(mediaIds).stream()
                .map(view -> new SignedDocument(view.mediaId(), view.readUrl(), view.readUrlExpiresAt()))
                .toList();
    }
}
