package com.fixup.media.infrastructure;

import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaMediaAssets implements MediaAssets {
    private final MediaAssetJpaRepository repository;

    JpaMediaAssets(MediaAssetJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(MediaAsset asset) {
        var entity = repository.findById(asset.id()).orElseGet(() -> MediaAssetEntity.from(asset));
        entity.apply(asset);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<MediaAsset> findById(UUID id) {
        return repository.findById(id).map(MediaAssetEntity::toDomain);
    }

    @Override
    public Optional<MediaAsset> findByIdForUpdate(UUID id) {
        return repository.lockById(id).map(MediaAssetEntity::toDomain);
    }
}
