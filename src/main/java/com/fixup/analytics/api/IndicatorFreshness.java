package com.fixup.analytics.api;

/**
 * FR-UC-15: qué tan confiable es el dato que se está devolviendo. El cliente nunca recibe un
 * indicador degradado disfrazado de fresco.
 */
public enum IndicatorFreshness {
    /** Obtenido de la fuente externa durante esta petición. */
    LIVE,
    /** Servido de la caché, todavía dentro de la ventana de frescura. */
    CACHED,
    /** La fuente no respondió: se sirve el último valor conocido, ya fuera de la ventana. */
    DEGRADED
}
