package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.domain.GeocodingCandidate;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReverseGeocodeAddress {
    private final GeocodingProvider provider;
    private final CurrentActorProvider actors;

    ReverseGeocodeAddress(GeocodingProvider provider, CurrentActorProvider actors) {
        this.provider = provider;
        this.actors = actors;
    }

    public List<GeocodingCandidate> execute(BigDecimal latitude, BigDecimal longitude) {
        PropertyAccess.requireCanGeocode(actors.currentActor());
        if (latitude == null || longitude == null
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Coordinates are outside valid ranges");
        }
        return List.copyOf(provider.reverseGeocode(latitude, longitude));
    }
}
