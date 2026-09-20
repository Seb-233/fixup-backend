package com.fixup.analytics.domain;

/**
 * Puerto hacia la fuente externa de indicadores. El dominio no sabe si detrás hay HTTP, un
 * archivo o un proveedor comercial: solo que puede no responder.
 *
 * @throws MarketSourceUnavailableException cuando la fuente no entrega un dato utilizable
 */
public interface MarketIndicatorsSource {
    MarketIndicators fetch(String zone);
}
