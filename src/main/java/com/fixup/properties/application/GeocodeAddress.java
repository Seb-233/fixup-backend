package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.domain.GeocodingCandidate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GeocodeAddress {
    public static final int MAX_QUERY_LENGTH = 200;

    private final GeocodingProvider provider;
    private final CurrentActorProvider currentActorProvider;

    public GeocodeAddress(GeocodingProvider provider, CurrentActorProvider currentActorProvider) {
        this.provider = provider;
        this.currentActorProvider = currentActorProvider;
    }

    public List<GeocodingCandidate> execute(String query) {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireCanGeocode(actor);
        return List.copyOf(provider.geocode(normalize(query)));
    }

    static String normalize(String query) {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        String normalized = query.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query must not exceed " + MAX_QUERY_LENGTH + " characters");
        }
        return normalized;
    }
}
