package com.fixup.properties.domain;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;

/** FR-UC-06: filtros del catálogo de inmuebles publicados. Un filtro nulo no restringe nada. */
public record CatalogQuery(String city, ListingType listingType, PropertyType propertyType, Long minPrice,
        Long maxPrice, Integer minBedrooms, Integer stratum, boolean sealOnly, String text, CatalogSort sort,
        int page, int size) {

    public static final int MAX_PAGE_SIZE = 48;

    public CatalogQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new IllegalArgumentException("minPrice must not be greater than maxPrice");
        }
        city = blankToNull(city);
        text = blankToNull(text);
        sort = sort == null ? CatalogSort.NEWEST : sort;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum CatalogSort { NEWEST, PRICE_ASC, PRICE_DESC, AREA_DESC }
}
