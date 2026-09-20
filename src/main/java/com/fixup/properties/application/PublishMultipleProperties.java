package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.Properties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishMultipleProperties {
    private final Properties properties;
    private final ApplicationEventPublisher events;

    public PublishMultipleProperties(Properties properties, ApplicationEventPublisher events) {
        this.properties = properties;
        this.events = events;
    }

    @Transactional
    public BatchResult execute(CurrentActor actor, List<PublishProperty.NewProperty> batch,
            PropertyStatus initialStatus) {
        if (batch == null || batch.isEmpty()) {
            return new BatchResult(List.of(), 0);
        }
        var now = Instant.now();
        var status = initialStatus == null ? PropertyStatus.DRAFT : initialStatus;
        var createdSummaries = new ArrayList<PropertySummary>(batch.size());
        var publishedIds = new ArrayList<UUID>();
        var distinctManagerIds = new java.util.HashSet<UUID>();
        for (var data : batch) {
            var id = UUID.randomUUID();
            var managerId = data.managerUserId();
            if (managerId != null) distinctManagerIds.add(managerId);
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
                publishedIds.add(property.id());
            }
            properties.create(property);
            createdSummaries.add(PropertySummary.of(property));
        }
        if (!publishedIds.isEmpty()) {
            UUID batchManagerUserId = distinctManagerIds.size() == 1
                    ? distinctManagerIds.iterator().next() : null;
            events.publishEvent(new com.fixup.properties.api.PropertyBatchPublished(
                    List.copyOf(publishedIds), actor.internalUserId(),
                    batchManagerUserId, publishedIds.size(), now));
        }
        return new BatchResult(createdSummaries, publishedIds.size());
    }

    public record BatchResult(List<PropertySummary> created, int publishedCount) {
    }
}
