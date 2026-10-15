package com.fixup.remodelings.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum RemodelingProgressStatus {
    OPEN,
    PARTIALLY_ASSIGNED,
    FULLY_ASSIGNED,
    PARTIALLY_COMPLETED,
    COMPLETED
}
