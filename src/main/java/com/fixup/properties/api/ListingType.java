package com.fixup.properties.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** FR-UC-02: el aviso ofrece el inmueble en arriendo o en venta. */
@Schema(enumAsRef = true)
public enum ListingType {
    RENT,
    SALE
}
