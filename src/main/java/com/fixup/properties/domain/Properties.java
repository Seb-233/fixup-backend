package com.fixup.properties.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;

public interface Properties {
    Property save(Property property);
    List<Property> saveAll(List<Property> properties);
    Optional<Property> findById(UUID id);
    List<Property> findAllById(Collection<UUID> ids);
    List<Property> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
    /** FR-UC-11: los inmuebles que el usuario posee o administra, más recientes primero. */
    List<Property> findControlledBy(UUID userId);
    Page<Property> searchCatalog(CatalogQuery query);
    List<PropertySearchMatch> searchPublished(
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal radiusKm,
            UUID currentUserId,
            OwnershipScope ownershipScope,
            GeographicBoundingBox boundingBox);
}
