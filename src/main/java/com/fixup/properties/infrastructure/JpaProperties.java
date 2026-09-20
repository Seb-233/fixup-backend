package com.fixup.properties.infrastructure;

import com.fixup.properties.api.PropertyDirectory;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertySnapshot;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class JpaProperties implements Properties, PropertyDirectory {

    private final PropertyJpaRepository jpa;

    public JpaProperties(PropertyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Property> findById(UUID id) {
        return jpa.findById(id).map(PropertyEntity::toDomain);
    }

    @Override
    public List<Property> findByIdIn(Collection<UUID> ids) {
        return jpa.findByIdIn(ids).stream().map(PropertyEntity::toDomain).toList();
    }

    @Override
    public Property create(Property property) {
        var entity = PropertyEntity.from(property);
        return jpa.save(entity).toDomain();
    }

    @Override
    public Property update(Property property) {
        var entity = PropertyEntity.from(property);
        return jpa.save(entity).toDomain();
    }

    @Override
    public List<Property> findByOwnerOrManager(UUID ownerUserId, UUID managerUserId,
            PropertyStatus status, UUID managerFilter, int limit) {
        return jpa.findByOwnerOrManager(ownerUserId, managerUserId, status, managerFilter,
                PageRequest.of(0, limit)).stream()
                .map(PropertyEntity::toDomain).toList();
    }

    @Override
    public List<Property> findPublished(PropertyType type, String city, String zone,
            BigDecimal minRent, BigDecimal maxRent, Integer minBedrooms, Integer minBathrooms,
            Double minSurface, Pageable page) {
        return jpa.findPublishedWithFilters(type, city, zone, minRent, maxRent,
                minBedrooms, minBathrooms, minSurface, page)
                .stream().map(PropertyEntity::toDomain).toList();
    }

    @Override
    public PropertySnapshot require(UUID propertyId) {
        var p = jpa.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
        return new PropertySnapshot(p.getId(), p.getOwnerUserId(), p.getManagerUserId(),
                p.getType(), p.getStatus(), p.getTitle(), p.getCity(), p.getZone(),
                p.getMonthlyRentSuggestion(), p.getBedrooms(), p.getBathrooms(), p.getSurfaceM2(),
                List.copyOf(p.getMediaIds()),
                p.getCreatedAt(), p.getPublishedAt());
    }
}
