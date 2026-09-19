package com.fixup.media.domain;

import java.util.Optional;
import java.util.UUID;

public interface MediaAssets {
    void save(MediaAsset asset);

    Optional<MediaAsset> findById(UUID id);

    Optional<MediaAsset> findByIdForUpdate(UUID id);
}
