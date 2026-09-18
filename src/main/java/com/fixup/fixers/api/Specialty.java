package com.fixup.fixers.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Closed taxonomy of trades offered by verified fixers. */
@Schema(enumAsRef = true)
public enum Specialty {
    PLUMBING, ELECTRICAL, PAINTING, CARPENTRY, MASONRY, GENERAL
}
