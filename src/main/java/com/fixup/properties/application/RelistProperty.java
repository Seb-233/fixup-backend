package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelistProperty {
    private final Properties properties;
    private final ApplicationEventPublisher events;

    public RelistProperty(Properties properties, ApplicationEventPublisher events) {
        this.properties = properties;
        this.events = events;
    }

    @Transactional
    public PropertySummary execute(CurrentActor actor, UUID propertyId) {
        var property = properties.findById(propertyId).orElseThrow(() -> new PropertyNotFoundException(propertyId));
        if (!property.isOwnedOrManagedBy(actor.internalUserId())) {
            throw new PropertyAccessDeniedException();
        }
        var now = Instant.now();
        var relisted = property.relist(now);
        properties.update(relisted);
        events.publishEvent(new com.fixup.properties.api.PropertyPublished(relisted.id(),
                relisted.ownerUserId(), relisted.managerUserId(),
                relisted.type(), relisted.title(), now));
        return PropertySummary.of(relisted);
    }
}
