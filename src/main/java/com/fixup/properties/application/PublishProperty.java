package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertyPublished;
import com.fixup.properties.domain.Properties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-12: el propietario publica un inmueble que ya creó. Publicar es una transición sobre la
 * fila existente y no un alta nueva, porque el inmueble puede llevar tiempo en borrador y porque
 * repair_requests ya lo referencia por su identificador (FR-UC-04).
 */
@Service
@Transactional
public class PublishProperty {
    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;
    private final ApplicationEventPublisher events;

    public PublishProperty(Properties properties, CurrentActorProvider currentActorProvider,
            ApplicationEventPublisher events) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
        this.events = events;
    }

    public PropertySummary execute(UUID propertyId, PublicationDetails details) {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireActiveOwner(actor);

        var property = properties.findById(propertyId)
            .orElseThrow(() -> new PropertyNotFoundException(propertyId));
        PropertyAccess.requireCanPublish(actor, property);

        var now = Instant.now();
        property.publish(details.type(), details.title(), details.description(), details.zone(),
            details.monthlyRentSuggestion(), now);
        var saved = properties.save(property);

        events.publishEvent(new PropertyPublished(saved.id(), saved.ownerUserId(), saved.type(),
            saved.title(), now));

        return PropertySummary.from(saved);
    }
}
