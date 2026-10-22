package com.fixup.properties.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum PropertyType {
    APARTMENT,
    HOUSE,
    STUDIO,
    OFFICE,
    COMMERCIAL,
    COUNTRY_HOUSE
}
