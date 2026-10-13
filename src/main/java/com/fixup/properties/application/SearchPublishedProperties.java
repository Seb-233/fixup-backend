package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.domain.GeographicBoundingBox;
import com.fixup.properties.domain.OwnershipScope;
import com.fixup.properties.domain.Properties;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchPublishedProperties {
    static final double EARTH_RADIUS_KM = 6371.0088;

    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;

    public SearchPublishedProperties(Properties properties, CurrentActorProvider currentActorProvider) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
    }

    @Transactional(readOnly = true)
    public List<PropertySearchResult> execute(
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal radiusKm,
            OwnershipScope ownershipScope) {
        validate(latitude, longitude, radiusKm);
        var effectiveScope = ownershipScope == null ? OwnershipScope.ALL : ownershipScope;
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireCanSearch(actor, effectiveScope);

        var boundingBox = boundingBox(latitude.doubleValue(), longitude.doubleValue(), radiusKm.doubleValue());
        return properties.searchPublished(
                        latitude,
                        longitude,
                        radiusKm,
                        actor.internalUserId(),
                        effectiveScope,
                        boundingBox)
                .stream()
                .map(match -> PropertySearchResult.from(match, actor))
                .toList();
    }

    static GeographicBoundingBox boundingBox(double latitude, double longitude, double radiusKm) {
        double angularDistance = radiusKm / EARTH_RADIUS_KM;
        double latitudeDelta = Math.toDegrees(angularDistance);
        double minLatitude = Math.max(-90.0, latitude - latitudeDelta);
        double maxLatitude = Math.min(90.0, latitude + latitudeDelta);

        if (minLatitude <= -90.0 || maxLatitude >= 90.0 || angularDistance >= Math.PI) {
            return fullLongitudeBox(minLatitude, maxLatitude);
        }

        double longitudeRatio = Math.sin(angularDistance) / Math.cos(Math.toRadians(latitude));
        if (!Double.isFinite(longitudeRatio) || Math.abs(longitudeRatio) >= 1.0) {
            return fullLongitudeBox(minLatitude, maxLatitude);
        }
        double longitudeDelta = Math.toDegrees(Math.asin(Math.abs(longitudeRatio)));

        double minLongitude = normalizeLongitude(longitude - longitudeDelta);
        double maxLongitude = normalizeLongitude(longitude + longitudeDelta);
        return new GeographicBoundingBox(
                BigDecimal.valueOf(minLatitude),
                BigDecimal.valueOf(maxLatitude),
                BigDecimal.valueOf(minLongitude),
                BigDecimal.valueOf(maxLongitude),
                false,
                minLongitude > maxLongitude);
    }

    private static GeographicBoundingBox fullLongitudeBox(double minLatitude, double maxLatitude) {
        return new GeographicBoundingBox(
                BigDecimal.valueOf(minLatitude),
                BigDecimal.valueOf(maxLatitude),
                BigDecimal.valueOf(-180),
                BigDecimal.valueOf(180),
                true,
                false);
    }

    private static double normalizeLongitude(double longitude) {
        double normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return normalized == -0.0 ? 0.0 : normalized;
    }

    private static void validate(BigDecimal latitude, BigDecimal longitude, BigDecimal radiusKm) {
        if (latitude == null || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude == null || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        if (radiusKm == null || radiusKm.signum() <= 0) {
            throw new IllegalArgumentException("radiusKm must be greater than zero");
        }
        if (!Double.isFinite(radiusKm.doubleValue())) {
            throw new IllegalArgumentException("radiusKm must be finite");
        }
    }
}
