package com.fixup.visits.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum ChecklistResult {
    OK,
    NEEDS_WORK,
    NOT_APPLICABLE
}
