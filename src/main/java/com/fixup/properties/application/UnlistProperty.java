package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertyUnlisted;
import com.fixup.properties.domain.Properties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-12: el propietario retira de la oferta un inmueble que había publicado. */
@Service
@Transactional
public class UnlistProperty {
    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;
    private final ApplicationEventPublisher events;

    public UnlistProperty(Properties properties, CurrentActorProvider currentActorProvider,
            ApplicationEventPublisher events) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
        this.events = events;
    }

    public PropertySummary execute(UUID propertyId) {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireActiveOwner(actor);

        var property = properties.findById(propertyId)
            .orElseThrow(() -> new PropertyNotFoundException(propertyId));
        PropertyAccess.requireCanPublish(actor, property);

        var now = Instant.now();
        property.unlist(now);
        var saved = properties.save(property);

        events.publishEvent(new PropertyUnlisted(saved.id(), saved.ownerUserId(), saved.title(), now));

        return PropertySummary.from(saved);
    }
}
