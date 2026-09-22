package com.fixup.properties.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** FR-UC-12: taxonomía cerrada de inmuebles publicables. */
@Schema(enumAsRef = true)
public enum PropertyType {
    APARTMENT, HOUSE, STORE, OFFICE, STUDIO, GARAGE, LAND
}
