package com.fixup.properties.application;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Property;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PropertySummary(
        UUID id,
        UUID ownerUserId,
        UUID managerUserId,
        PropertyType type,
        PropertyStatus status,
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
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant unlistedAt) {

    public static PropertySummary of(Property p) {
        return new PropertySummary(p.id(), p.ownerUserId(), p.managerUserId(), p.type(), p.status(),
                p.title(), p.description(), p.addressStreet(), p.addressNumber(), p.addressFloor(),
                p.addressApartment(), p.city(), p.zone(), p.postalCode(),
                p.latitude(), p.longitude(), p.surfaceM2(), p.coveredSurfaceM2(),
                p.bedrooms(), p.bathrooms(), p.coveredParkingSpots(),
                p.hasBalcony(), p.hasTerrace(), p.hasGarden(),
                p.hasElevator(), p.hasPool(), p.hasSecurity(),
                p.petsAllowed(), p.furnished(),
                List.copyOf(p.amenities()), p.monthlyRentSuggestion(), p.monthlyCondoFee(),
                List.copyOf(p.mediaIds()),
                p.createdAt(), p.updatedAt(), p.publishedAt(), p.unlistedAt());
    }
}
