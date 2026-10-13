package com.fixup.properties.application;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {"id", "name", "address", "city", "areaM2", "status", "photoMediaIds"})
public record PropertySummary(
    UUID id,
    String name,
    String address,
    String city,
    BigDecimal areaM2,
    PropertyStatus status,
    Instant publishedAt,
    UUID ownerUserId,
    @Schema(types = {"string", "null"}) UUID managerUserId,
    @Schema(types = {"number", "null"}) BigDecimal latitude,
    @Schema(types = {"number", "null"}) BigDecimal longitude,
    @Schema(types = {"string", "null"}) ListingType listingType,
    @Schema(types = {"string", "null"}) PropertyType propertyType,
    @Schema(types = {"integer", "null"}) Long price,
    @Schema(types = {"integer", "null"}) Long adminFee,
    @Schema(types = {"integer", "null"}) Integer bedrooms,
    @Schema(types = {"integer", "null"}) Integer bathrooms,
    @Schema(types = {"integer", "null"}) Integer parkingSpots,
    @Schema(types = {"integer", "null"}) Integer stratum,
    @Schema(types = {"string", "null"}) String neighborhood,
    @Schema(types = {"string", "null"}) String description,
    List<UUID> photoMediaIds
) {
    public static PropertySummary from(Property property) {
        var listing = property.listing();
        return new PropertySummary(
            property.id(),
            property.name(),
            property.address(),
            property.city(),
            property.areaM2(),
            property.status(),
            property.publishedAt(),
            property.ownerUserId(),
            property.managerUserId(),
            property.latitude(),
            property.longitude(),
            listing.listingType(),
            listing.propertyType(),
            listing.price(),
            listing.adminFee(),
            listing.bedrooms(),
            listing.bathrooms(),
            listing.parkingSpots(),
            listing.stratum(),
            listing.neighborhood(),
            listing.description(),
            property.photoMediaIds()
        );
    }
}
