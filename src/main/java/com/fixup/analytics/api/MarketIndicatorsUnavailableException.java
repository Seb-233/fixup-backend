package com.fixup.analytics.api;

/**
 * La fuente externa no respondió y no existe ningún valor conocido para la zona. No hay nada
 * que degradar: se informa la indisponibilidad en lugar de inventar un indicador.
 */
public class MarketIndicatorsUnavailableException extends RuntimeException {
    public MarketIndicatorsUnavailableException(String zone) {
        super("No market indicators are available for zone " + zone);
    }
}
