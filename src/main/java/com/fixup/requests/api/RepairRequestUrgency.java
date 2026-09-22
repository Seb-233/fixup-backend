package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;

/**
 * FR-UC-08: la urgencia que declara el propietario y el plazo que la plataforma se compromete a
 * cumplir para cada una. La ventana vive junto a la urgencia y no en una tabla de parámetros
 * porque es parte de la definición del nivel, no una configuración de despliegue.
 */
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
