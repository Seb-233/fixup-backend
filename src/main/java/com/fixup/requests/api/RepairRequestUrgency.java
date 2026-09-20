package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;

@Schema(enumAsRef = true)
public enum RepairRequestUrgency {
    LOW(Duration.ofDays(7)),
    MEDIUM(Duration.ofDays(3)),
    HIGH(Duration.ofHours(24)),
    URGENT(Duration.ofHours(4));

    private final Duration slaWindow;

    RepairRequestUrgency(Duration slaWindow) {
        this.slaWindow = slaWindow;
    }

    public Duration slaWindow() {
        return slaWindow;
    }
}
