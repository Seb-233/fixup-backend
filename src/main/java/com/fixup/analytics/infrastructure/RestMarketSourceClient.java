package com.fixup.analytics.infrastructure;

import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adaptador HTTP configurable para el día en que exista un proveedor contratado. La forma de la
 * respuesta esperada se declara en {@link ProviderPayload}; el caso de uso no la conoce.
 *
 * <p>Aquí vive la táctica <b>Timeout</b>: los tiempos de conexión y lectura se fijan en la fábrica
 * de peticiones, de modo que una fuente que no responde falla rápido y de forma observable en vez
 * de agotar el hilo. Los reintentos son responsabilidad de {@link TimeoutAndRetryMarketSource}.
 *
 * <p>Un payload al que le falte precio, variación u {@code observedAt} se considera inválido y se
 * trata como indisponibilidad. Este adaptador no rellena huecos: la frescura que se le reporta al
 * cliente depende de que observedAt venga del proveedor.
 */
@Component
@ConditionalOnProperty(name = "fixup.analytics.market-source.provider", havingValue = "rest")
class RestMarketSourceClient implements MarketSourceClient {
    private final RestClient restClient;
    private final String baseUrl;

    RestMarketSourceClient(MarketSourceProperties properties) {
        this.baseUrl = properties.getBaseUrl();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
    }

    @Override
    public MarketIndicators fetchOnce(String zone) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new MarketSourceUnavailableException(
                    "The market source is set to rest but fixup.analytics.market-source.base-url is empty");
        }
        try {
            return indicatorsOf(zone, restClient.get().uri("/indicators/{zone}", zone)
                    .retrieve().body(ProviderPayload.class));
        } catch (RestClientException failure) {
            // El mensaje del proveedor no se propaga al cliente: podría llevar URLs o credenciales.
            throw new MarketSourceUnavailableException("The market source did not answer", failure);
        }
    }

    /**
     * Un payload incompleto no se completa desde aquí. Poner {@code Instant.now()} en lugar del
     * observedAt que faltaba convertiría un dato de antigüedad desconocida en uno aparentemente
     * recién observado, y la frescura que se le reporta al cliente dejaría de significar algo. Un
     * proveedor que no entrega observedAt es un proveedor que no entrega un indicador utilizable.
     */
    static MarketIndicators indicatorsOf(String zone, ProviderPayload payload) {
        if (payload == null
                || payload.pricePerSquareMeter() == null
                || payload.yearOverYearVariationPercent() == null
                || payload.observedAt() == null) {
            throw new MarketSourceUnavailableException("The market source returned an incomplete payload");
        }
        return new MarketIndicators(zone, payload.pricePerSquareMeter(),
                payload.yearOverYearVariationPercent(), payload.averageDaysOnMarket(),
                payload.observedAt(), IndicatorSource.EXTERNAL_PROVIDER);
    }

    @Override
    public String describe() {
        return "rest";
    }

    record ProviderPayload(BigDecimal pricePerSquareMeter, BigDecimal yearOverYearVariationPercent,
            int averageDaysOnMarket, Instant observedAt) {
    }
}
