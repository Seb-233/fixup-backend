package com.fixup.remodelings.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IMPROVEMENT: remodelar para vivir mejor o valorizar. PRE_RENTAL (FR-UC-26): remodelar para
 * arrendar, con un canon objetivo con el que el inmueble se publica al terminar.
 */
@Schema(enumAsRef = true)
public enum RemodelingGoal {
    IMPROVEMENT,
    PRE_RENTAL
}
