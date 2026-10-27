package com.fixup.properties.domain;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;

/**
 * FR-UC-02: los datos del aviso. Todos son opcionales para que un inmueble registrado solo con
 * nombre, dirección y área siga siendo válido; los que vienen se validan aquí y no en la pantalla.
 */
public record PropertyListing(ListingType listingType, PropertyType propertyType, Long price, Long adminFee,
        Integer bedrooms, Integer bathrooms, Integer parkingSpots, Integer stratum, String neighborhood,
        String description) {

    public static final PropertyListing EMPTY =
            new PropertyListing(null, null, null, null, null, null, null, null, null, null);
    private static final long MAX_AMOUNT = 9_007_199_254_740_991L;

    public PropertyListing {
        if (price != null && (price <= 0 || price > MAX_AMOUNT)) {
            throw new IllegalArgumentException("price must be a positive amount in pesos");
        }
        if (adminFee != null && (adminFee < 0 || adminFee > MAX_AMOUNT)) {
            throw new IllegalArgumentException("adminFee must not be negative");
        }
        requireRange(bedrooms, "bedrooms", 0, 20);
        requireRange(bathrooms, "bathrooms", 0, 20);
        requireRange(parkingSpots, "parkingSpots", 0, 20);
        requireRange(stratum, "stratum", 1, 6);
        neighborhood = trimToNull(neighborhood, "neighborhood", 120);
        description = trimToNull(description, "description", 4000);
        if (listingType != null && price == null) {
            throw new IllegalArgumentException("A listing for " + listingType + " needs a price");
        }
    }

    public boolean isEmpty() {
        return this.equals(EMPTY);
    }

    private static void requireRange(Integer value, String field, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
    }

    private static String trimToNull(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
