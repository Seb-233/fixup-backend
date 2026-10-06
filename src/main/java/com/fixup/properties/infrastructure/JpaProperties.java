package com.fixup.properties.infrastructure;

import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import org.springframework.stereotype.Component;

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
        return repository.save(new PropertyEntity(property)).toDomain();
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
    public List<Property> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId) {
        return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)
            .stream()
            .map(PropertyEntity::toDomain)
            .toList();
    }
}
