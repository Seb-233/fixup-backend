package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.api.PropertyListingPublisher;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertyPublished;
import com.fixup.properties.api.PropertySnapshot;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.PropertyListing;
import com.fixup.properties.api.ListingType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-02: publicar es gratuito y reversible. Retirar el aviso no borra el inmueble ni su
 * historial; volver a publicarlo lo devuelve al catálogo con una nueva fecha de publicación.
 */
@Service
public class ChangePropertyPublication implements PropertyListingPublisher {
    private final Properties properties;
    private final CurrentActorProvider actors;
    private final ApplicationEventPublisher events;

    ChangePropertyPublication(Properties properties, CurrentActorProvider actors, ApplicationEventPublisher events) {
        this.properties = properties;
        this.actors = actors;
        this.events = events;
    }

    @Transactional
    public PropertySummary publish(UUID propertyId) {
        var actor = actors.currentActor();
        var property = load(propertyId);
        PropertyAccess.requireCanManage(actor, property);
        publish(actor, property);
        return PropertySummary.from(properties.save(property));
    }

    @Transactional
    public PropertySummary unpublish(UUID propertyId) {
        var actor = actors.currentActor();
        var property = load(propertyId);
        PropertyAccess.requireCanManage(actor, property);
        property.unpublish(Instant.now());
        return PropertySummary.from(properties.save(property));
    }

    /** FR-UC-26: al terminar la remodelación pre-arriendo, el inmueble sale en arriendo con el canon meta. */
    @Override
    @Transactional
    public PropertySnapshot publishForRent(CurrentActor actor, UUID propertyId, long monthlyRent) {
        var property = load(propertyId);
        PropertyAccess.requireCanManage(actor, property);
        var listing = property.listing();
        property.updateDetails(property.name(), property.address(), property.city(), property.areaM2(),
                property.latitude(), property.longitude(),
                new PropertyListing(ListingType.RENT, listing.propertyType(), monthlyRent, listing.adminFee(),
                        listing.bedrooms(), listing.bathrooms(), listing.parkingSpots(), listing.stratum(),
                        listing.neighborhood(), listing.description()),
                Instant.now());
        publish(actor, property);
        return PropertySnapshots.of(properties.save(property));
    }

    private void publish(CurrentActor actor, Property property) {
        if (property.publish(actor.internalUserId(), Instant.now())) {
            events.publishEvent(new PropertyPublished(property.id(), property.ownerUserId(), property.name()));
        }
    }

    private Property load(UUID propertyId) {
        return properties.findById(propertyId).orElseThrow(() -> new PropertyNotFoundException(propertyId));
    }
}
