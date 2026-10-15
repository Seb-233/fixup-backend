package com.fixup.shared.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita scheduling global para jobs de SLA, vencimientos de contratos y otros
 * trabajos recurrentes de la aplicación. A diferencia de MediaSchedulingConfiguration,
 * esta clase no está condicionada a una property y siempre activa los schedulers.
 */
@Configuration
@EnableScheduling
public class ApplicationSchedulingConfiguration {
}
