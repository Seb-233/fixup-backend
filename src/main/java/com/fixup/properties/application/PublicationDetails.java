package com.fixup.properties.application;

import com.fixup.properties.api.PropertyType;

import java.math.BigDecimal;

/** FR-UC-12: los datos con los que un inmueble sale a la oferta. */
public record PublicationDetails(
    PropertyType type,
    String title,
    String description,
    String zone,
    BigDecimal monthlyRentSuggestion
) {}
