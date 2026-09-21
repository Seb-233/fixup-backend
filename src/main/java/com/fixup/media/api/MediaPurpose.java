package com.fixup.media.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Typed purposes for uploaded media assets.
 */
@Schema(enumAsRef = true)
public enum MediaPurpose {
    FIXER_PORTFOLIO,
    REPAIR_REQUEST,
    FIXER_VERIFICATION
}
