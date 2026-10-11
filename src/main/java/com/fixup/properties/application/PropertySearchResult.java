package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.properties.domain.PropertySearchMatch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(requiredProperties = {
    "id", "name", "address", "city", "areaM2", "latitude", "longitude", "distanceKm",
    "ownedByCurrentUser"
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
        boolean ownedByCurrentUser) {

    static PropertySearchResult from(PropertySearchMatch match, CurrentActor actor) {
        var property = match.property();
        return new PropertySearchResult(
                property.id(),
                property.name(),
                property.address(),
                property.city(),
                property.areaM2(),
                property.latitude(),
                property.longitude(),
                match.distanceKm(),
                actor.hasRole(Role.OWNER) && property.ownerUserId().equals(actor.internalUserId()));
    }
}
