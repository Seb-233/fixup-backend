package com.fixup.properties.infrastructure;

import com.fixup.properties.domain.CatalogQuery;
import com.fixup.properties.domain.GeographicBoundingBox;
import com.fixup.properties.domain.OwnershipScope;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.PropertySearchMatch;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class JpaProperties implements Properties {
    private final PropertyJpaRepository repository;

    JpaProperties(PropertyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Property save(Property property) {
        var entity = repository.findById(property.id())
                .map(existing -> {
                    existing.apply(property);
                    return existing;
                })
                .orElseGet(() -> new PropertyEntity(property));
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public List<Property> saveAll(List<Property> properties) {
        var entities = properties.stream().map(PropertyEntity::new).toList();
        return repository.saveAll(entities).stream().map(PropertyEntity::toDomain).toList();
    }

    @Override
    public Optional<Property> findById(UUID id) {
        return repository.findById(id).map(PropertyEntity::toDomain);
    }

    @Override
    public List<Property> findAllById(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findAllById(ids).stream().map(PropertyEntity::toDomain).toList();
    }

    @Override
    public List<Property> findControlledBy(UUID userId) {
        return repository.findControlledBy(userId).stream().map(PropertyEntity::toDomain).toList();
    }

    @Override
    public Page<Property> searchCatalog(CatalogQuery query) {
        return repository.findAll(CatalogSpecification.of(query), CatalogSpecification.pageRequest(query))
                .map(PropertyEntity::toDomain);
    }

    @Override
    public List<Property> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId) {
        return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)
            .stream()
            .map(PropertyEntity::toDomain)
            .toList();
    }

    @Override
    public List<PropertySearchMatch> searchPublished(
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal radiusKm,
            UUID currentUserId,
            OwnershipScope ownershipScope,
            GeographicBoundingBox boundingBox) {
        var distances = repository.searchPublishedIds(
                latitude,
                longitude,
                radiusKm,
                currentUserId,
                ownershipScope.name(),
                boundingBox.minLatitude(),
                boundingBox.maxLatitude(),
                boundingBox.minLongitude(),
                boundingBox.maxLongitude(),
                boundingBox.allLongitudes(),
                boundingBox.crossesAntimeridian());
        if (distances.isEmpty()) {
            return List.of();
        }

        var entitiesById = new HashMap<UUID, PropertyEntity>();
        repository.findAllById(distances.stream()
                        .map(distance -> UUID.fromString(distance.getId()))
                        .toList())
                .forEach(entity -> entitiesById.put(entity.id(), entity));

        return distances.stream()
                .map(distance -> new PropertySearchMatch(
                        requireEntity(entitiesById, UUID.fromString(distance.getId())).toDomain(),
                        distance.getDistanceKm()))
                .toList();
    }

    private PropertyEntity requireEntity(java.util.Map<UUID, PropertyEntity> entitiesById, UUID id) {
        var entity = entitiesById.get(id);
        if (entity == null) {
            throw new IllegalStateException("Geographic search result disappeared during the transaction");
        }
        return entity;
    }
}
