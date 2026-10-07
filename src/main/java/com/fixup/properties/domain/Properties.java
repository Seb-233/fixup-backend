package com.fixup.properties.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Properties {
    Property save(Property property);
    List<Property> saveAll(List<Property> properties);
    Optional<Property> findById(UUID id);
    List<Property> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
