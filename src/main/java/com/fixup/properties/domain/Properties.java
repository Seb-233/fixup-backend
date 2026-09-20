package com.fixup.properties.domain;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

public interface Properties {

    Optional<Property> findById(UUID id);

    List<Property> findByIdIn(Collection<UUID> ids);

    Property create(Property property);

    Property update(Property property);

    List<Property> findByOwnerOrManager(UUID ownerUserId, UUID managerUserId, PropertyStatus status,
            UUID managerFilter, int limit);

    List<Property> findPublished(PropertyType type, String city, String zone,
            BigDecimal minRent, BigDecimal maxRent, Integer minBedrooms, Integer minBathrooms,
            Double minSurface, org.springframework.data.domain.Pageable page);
}
