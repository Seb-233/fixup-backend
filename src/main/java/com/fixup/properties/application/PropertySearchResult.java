package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.PropertySearchMatch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(requiredProperties = {
    "id", "name", "address", "city", "areaM2", "latitude", "longitude", "distanceKm",
    "ownedByCurrentUser", "seal"
})
public record PropertySearchResult(
        UUID id,
        String name,
        String address,
        String city,
        BigDecimal areaM2,
        BigDecimal latitude,
        BigDecimal longitude,
        double distanceKm,
        boolean ownedByCurrentUser,
        @Schema(types = {"string", "null"}) String neighborhood,
        @Schema(types = {"string", "null"}) ListingType listingType,
        @Schema(types = {"string", "null"}) PropertyType propertyType,
        @Schema(types = {"integer", "null"}) Long price,
        @Schema(types = {"integer", "null"}) Integer bedrooms,
        @Schema(types = {"integer", "null"}) Integer bathrooms,
        @Schema(types = {"string", "null"}) String coverPhotoUrl,
        boolean seal) {

    static PropertySearchResult from(PropertySearchMatch match, CurrentActor actor) {
        return from(match, actor, null, false);
    }

    static PropertySearchResult from(PropertySearchMatch match, CurrentActor actor, String coverPhotoUrl,
            boolean seal) {
        var property = match.property();
        var listing = property.listing();
        return new PropertySearchResult(
                property.id(),
                property.name(),
                property.address(),
                property.city(),
                property.areaM2(),
                property.latitude(),
                property.longitude(),
                match.distanceKm(),
                actor.hasRole(Role.OWNER) && property.ownerUserId().equals(actor.internalUserId()),
                listing.neighborhood(),
                listing.listingType(),
                listing.propertyType(),
                listing.price(),
                listing.bedrooms(),
                listing.bathrooms(),
                coverPhotoUrl,
                seal);
    }
}
