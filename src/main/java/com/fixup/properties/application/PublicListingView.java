package com.fixup.properties.application;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-02, FR-UC-06 y FR-UC-07: el aviso como lo ve quien busca vivienda. Muestra la dirección
 * completa, las fotos, el sello y el historial de trabajos hechos por técnicos de la plataforma.
 */
@Schema(requiredProperties = {"id", "name", "address", "city", "areaM2", "status", "photos", "seal",
    "workHistory", "ownedByCurrentUser"})
public record PublicListingView(
        UUID id,
        String name,
        String address,
        String city,
        @Schema(types = {"string", "null"}) String neighborhood,
        BigDecimal areaM2,
        PropertyStatus status,
        @Schema(types = {"string", "null"}) ListingType listingType,
        @Schema(types = {"string", "null"}) PropertyType propertyType,
        @Schema(types = {"integer", "null"}) Long price,
        @Schema(types = {"integer", "null"}) Long adminFee,
        @Schema(types = {"integer", "null"}) Integer bedrooms,
        @Schema(types = {"integer", "null"}) Integer bathrooms,
        @Schema(types = {"integer", "null"}) Integer parkingSpots,
        @Schema(types = {"integer", "null"}) Integer stratum,
        @Schema(types = {"string", "null"}) String description,
        @Schema(types = {"number", "null"}) BigDecimal latitude,
        @Schema(types = {"number", "null"}) BigDecimal longitude,
        @Schema(types = {"string", "null"}) Instant publishedAt,
        @Schema(types = {"string", "null"}) String ownerDisplayName,
        List<Photo> photos,
        Seal seal,
        List<Work> workHistory,
        boolean ownedByCurrentUser) {

    @Schema(requiredProperties = {"mediaId", "url"})
    public record Photo(UUID mediaId, String url) {
    }

    @Schema(requiredProperties = {"granted", "recentCompletedWorks"})
    public record Seal(boolean granted, int recentCompletedWorks,
            @Schema(types = {"number", "null"}) Double averageRating,
            @Schema(types = {"string", "null"}) Instant lastWorkAt) {
    }

    @Schema(requiredProperties = {"jobId", "specialty", "title", "fixerUserId", "completedAt"})
    public record Work(UUID jobId, String specialty, String title, UUID fixerUserId,
            @Schema(types = {"string", "null"}) String fixerDisplayName, Instant completedAt,
            @Schema(types = {"integer", "null"}) Integer rating) {
    }
}
