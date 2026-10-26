package com.fixup.properties.application;

import com.fixup.properties.api.PropertyDirectory;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertySnapshot;
import com.fixup.properties.domain.Properties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class PropertyDirectoryImpl implements PropertyDirectory {

    private final Properties properties;

    PropertyDirectoryImpl(Properties properties) {
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public PropertySnapshot requireOwnedBy(UUID propertyId, UUID ownerUserId) {
        var property = properties.findById(propertyId)
            .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.ownerUserId().equals(ownerUserId)) {
            throw new PropertyNotFoundException(propertyId);
        }
        return PropertySnapshots.of(property);
    }

    @Override
    @Transactional(readOnly = true)
    public PropertySnapshot require(UUID propertyId) {
        return properties.findById(propertyId)
                .map(PropertySnapshots::of)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public PropertySnapshot lockForFinancialDecision(UUID propertyId) {
        return properties.findByIdForShare(propertyId).map(PropertySnapshots::of)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> propertyIds) {
        var names = new HashMap<UUID, String>();
        properties.findAllById(propertyIds).forEach(property -> names.put(property.id(), property.name()));
        return names;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropertySnapshot> findAll(Collection<UUID> propertyIds) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            return List.of();
        }
        return properties.findAllById(propertyIds).stream().map(PropertySnapshots::of).toList();
    }
}
