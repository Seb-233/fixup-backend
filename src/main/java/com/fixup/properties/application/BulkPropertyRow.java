package com.fixup.properties.application;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.PropertyListing;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Una fila del archivo de carga masiva (FR-UC-12). Las cuatro columnas originales son
 * obligatorias; las del aviso (FR-UC-02) y las coordenadas son opcionales y viajan en extras.
 */
public record BulkPropertyRow(
    int rowNumber,
    String name,
    String address,
    String city,
    String areaM2Str,
    Map<String, String> extras
) {
    public BulkPropertyRow {
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    public BulkPropertyRow(int rowNumber, String name, String address, String city, String areaM2Str) {
        this(rowNumber, name, address, city, areaM2Str, Map.of());
    }

    PropertyListing listing() {
        return new PropertyListing(
                enumValue(ListingType.class, "listingType"),
                enumValue(PropertyType.class, "propertyType"),
                longValue("price"),
                longValue("adminFee"),
                intValue("bedrooms"),
                intValue("bathrooms"),
                intValue("parkingSpots"),
                intValue("stratum"),
                text("neighborhood"),
                text("description"));
    }

    BigDecimal latitude() {
        return decimal("latitude");
    }

    BigDecimal longitude() {
        return decimal("longitude");
    }

    private String text(String column) {
        var value = extras.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long longValue(String column) {
        var value = text(column);
        return value == null ? null : Long.valueOf(value.replace(".", "").replace(",", ""));
    }

    private Integer intValue(String column) {
        var value = text(column);
        return value == null ? null : Integer.valueOf(value);
    }

    private BigDecimal decimal(String column) {
        var value = text(column);
        return value == null ? null : new BigDecimal(value);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String column) {
        var value = text(column);
        return value == null ? null : Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
