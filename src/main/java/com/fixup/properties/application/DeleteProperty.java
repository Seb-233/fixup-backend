package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeleteProperty {
    private final Properties properties;

    public DeleteProperty(Properties properties) {
        this.properties = properties;
    }

    @Transactional
    public PropertySummary execute(CurrentActor actor, UUID propertyId) {
        var property = properties.findById(propertyId).orElseThrow(() -> new PropertyNotFoundException(propertyId));
        if (!property.isOwnedOrManagedBy(actor.internalUserId())) {
            throw new PropertyAccessDeniedException();
        }
        var deleted = property.delete(Instant.now());
        properties.update(deleted);
        return PropertySummary.of(deleted);
    }
}
