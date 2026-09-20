package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum RepairRequestStatus {
    OPEN, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, ON_HOLD
}
