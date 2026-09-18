package com.fixup.analytics.domain;

/**
 * Táctica de detección de defectos "Timeout: lanzar y manejar excepciones" (25-ago): la fuente
 * externa señala su indisponibilidad con una excepción, nunca con un valor vacío o inventado.
 */
public class MarketSourceUnavailableException extends RuntimeException {
    public MarketSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public MarketSourceUnavailableException(String message) {
        super(message);
    }
}
