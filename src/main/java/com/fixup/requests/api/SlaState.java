package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum SlaState {
    NONE, ON_TRACK, WARNING, BREACHED
}
