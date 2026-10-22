package com.fixup.visits.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** PROPOSED espera a que la contraparte confirme; reprogramar vuelve a PROPOSED. */
@Schema(enumAsRef = true)
public enum VisitStatus {
    PROPOSED,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
