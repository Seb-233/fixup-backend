package com.fixup.media.application;

import java.util.UUID;

public record MediaDeletionRequested(UUID jobId, UUID mediaAssetId, String objectKey) {
}
