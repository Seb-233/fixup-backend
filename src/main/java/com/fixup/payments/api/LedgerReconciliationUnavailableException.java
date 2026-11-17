package com.fixup.payments.api;

/**
 * La conciliación no produjo un resultado: no es "sin hallazgos". Está apagada, no se pudo leer el
 * libro, u otra instancia ya lo está conciliando.
 */
public class LedgerReconciliationUnavailableException extends RuntimeException {
    private final String code;

    public LedgerReconciliationUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
