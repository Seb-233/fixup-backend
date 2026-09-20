package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertySnapshot;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.Properties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishProperty {
    private final Properties properties;
    private final ApplicationEventPublisher events;

    public PublishProperty(Properties properties, ApplicationEventPublisher events) {
        this.properties = properties;
        this.events = events;
    }

    @Transactional
    public PropertySummary execute(CurrentActor actor, NewProperty data, PropertyStatus initialStatus) {
        var now = Instant.now();
        var id = UUID.randomUUID();
        var status = initialStatus == null ? PropertyStatus.DRAFT : initialStatus;
        var managerId = data.managerUserId() != null ? data.managerUserId() : null;
        var property = Property.create(id, actor.internalUserId(), managerId,
                data.type(), data.title(), data.description(),
                data.addressStreet(), data.addressNumber(), data.addressFloor(), data.addressApartment(),
                data.city(), data.zone(), data.postalCode(),
                data.latitude(), data.longitude(),
                data.surfaceM2(), data.coveredSurfaceM2(),
                data.bedrooms(), data.bathrooms(), data.coveredParkingSpots(),
                data.hasBalcony(), data.hasTerrace(), data.hasGarden(),
                data.hasElevator(), data.hasPool(), data.hasSecurity(),
                data.petsAllowed(), data.furnished(),
                data.amenities(), data.monthlyRentSuggestion(), data.monthlyCondoFee(),
                data.mediaIds(), now);
        if (status == PropertyStatus.PUBLISHED) {
            property = property.publish(now);
        }
        properties.create(property);
        if (property.status() == PropertyStatus.PUBLISHED) {
            events.publishEvent(new com.fixup.properties.api.PropertyPublished(property.id(),
                    property.ownerUserId(), property.managerUserId(),
                    property.type(), property.title(), now));
        }
        return PropertySummary.of(property);
    }

    public record NewProperty(
            PropertyType type,
            String title,
            String description,
            String addressStreet,
            String addressNumber,
            String addressFloor,
            String addressApartment,
            String city,
            String zone,
            String postalCode,
            BigDecimal latitude,
            BigDecimal longitude,
            Double surfaceM2,
            Double coveredSurfaceM2,
            Integer bedrooms,
            Integer bathrooms,
            Integer coveredParkingSpots,
            Boolean hasBalcony,
            Boolean hasTerrace,
            Boolean hasGarden,
            Boolean hasElevator,
            Boolean hasPool,
            Boolean hasSecurity,
            Boolean petsAllowed,
            Boolean furnished,
            List<String> amenities,
            BigDecimal monthlyRentSuggestion,
            BigDecimal monthlyCondoFee,
            List<UUID> mediaIds,
            UUID managerUserId) {
    }
}
