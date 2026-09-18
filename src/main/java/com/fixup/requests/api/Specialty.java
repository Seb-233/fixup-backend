package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Closed taxonomy of trades. A request is routed to the fixers of a single specialty. */
@Schema(enumAsRef = true)
public enum Specialty {
    PLUMBING, ELECTRICAL, PAINTING, CARPENTRY, MASONRY, GENERAL
}
