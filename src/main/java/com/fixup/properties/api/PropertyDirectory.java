package com.fixup.properties.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public interface PropertyDirectory {

    PropertySnapshot require(UUID propertyId);

    default List<PropertySnapshot> requireMany(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(this::require).toList();
    }

    default Map<UUID, PropertySnapshot> mapById(Collection<UUID> ids) {
        return requireMany(ids).stream().collect(Collectors.toMap(PropertySnapshot::id, p -> p));
    }
}
