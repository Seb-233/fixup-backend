package com.fixup.properties.application;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.PropertyListing;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-02: alta del inmueble con su aviso. Los campos del aviso son opcionales; {@code publish}
 * nulo conserva el comportamiento original (publicar al registrar).
 */
public record NewProperty(
    String name,
    String address,
    String city,
    BigDecimal areaM2,
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
    ListingType listingType,
    PropertyType propertyType,
    Long price,
    Long adminFee,
    Integer bedrooms,
    Integer bathrooms,
    Integer parkingSpots,
    Integer stratum,
    @Size(max = 120) String neighborhood,
    @Size(max = 4000) String description,
    @Size(max = 12) List<@NotNull UUID> photoMediaIds,
    Boolean publish
) {
    public NewProperty(String name, String address, String city, BigDecimal areaM2, BigDecimal latitude,
            BigDecimal longitude) {
        this(name, address, city, areaM2, latitude, longitude, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    PropertyListing listing() {
        return new PropertyListing(listingType, propertyType, price, adminFee, bedrooms, bathrooms, parkingSpots,
                stratum, neighborhood, description);
    }
}
