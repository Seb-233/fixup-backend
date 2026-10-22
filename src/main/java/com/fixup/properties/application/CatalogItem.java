package com.fixup.properties.application;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** FR-UC-06 y FR-UC-07: una tarjeta del catálogo, con la foto de portada y el sello calculado. */
@Schema(requiredProperties = {"id", "name", "city", "areaM2", "photoCount", "seal", "completedWorks",
    "ownedByCurrentUser"})
public record CatalogItem(
        UUID id,
        String name,
        String city,
        @Schema(types = {"string", "null"}) String neighborhood,
        @Schema(types = {"string", "null"}) ListingType listingType,
        @Schema(types = {"string", "null"}) PropertyType propertyType,
        @Schema(types = {"integer", "null"}) Long price,
        @Schema(types = {"integer", "null"}) Long adminFee,
        @Schema(types = {"integer", "null"}) Integer bedrooms,
        @Schema(types = {"integer", "null"}) Integer bathrooms,
        BigDecimal areaM2,
        @Schema(types = {"integer", "null"}) Integer stratum,
        @Schema(types = {"number", "null"}) BigDecimal latitude,
        @Schema(types = {"number", "null"}) BigDecimal longitude,
        @Schema(types = {"string", "null"}) String coverPhotoUrl,
        int photoCount,
        boolean seal,
        int completedWorks,
        @Schema(types = {"number", "null"}) Double averageRating,
        boolean ownedByCurrentUser,
        @Schema(types = {"string", "null"}) Instant publishedAt) {
}
