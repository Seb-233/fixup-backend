package com.fixup.properties.application;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.PropertyListing;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** FR-UC-02: edición completa del inmueble y su aviso; reemplaza las fotos por la lista dada. */
public record PropertyChanges(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String address,
    @NotBlank @Size(max = 255) String city,
    @NotNull BigDecimal areaM2,
    @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
    @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
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
    @Size(max = 12) List<@NotNull UUID> photoMediaIds
) {
    PropertyListing listing() {
        return new PropertyListing(listingType, propertyType, price, adminFee, bedrooms, bathrooms, parkingSpots,
                stratum, neighborhood, description);
    }
}
