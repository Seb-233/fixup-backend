package com.fixup.requests.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FR-UC-08: el barrido de SLA tiene su propio interruptor y no depende del de la poda de media.
 * Apagarlo no quita el servicio de aplicación, solo el reloj: las pruebas invocan el barrido
 * cuando quieren en lugar de esperar a que salte solo.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fixup.sla.enabled", havingValue = "true", matchIfMissing = true)
class RequestSchedulingConfiguration {
}
