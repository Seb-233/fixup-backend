package com.fixup.analytics.infrastructure;

import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
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
 * <p>Un payload al que le falte cualquiera de sus atributos requeridos o cuyos valores violen
 * las restricciones de negocio o persistencia se considera inválido y se trata como indisponibilidad
 * (nunca como un 400 del cliente de Fixup). Este adaptador no rellena huecos.
 */
@Component
@ConditionalOnProperty(name = "fixup.analytics.market-source.provider", havingValue = "rest")
class RestMarketSourceClient implements MarketSourceClient {

    private static final BigDecimal MIN_PRICE = BigDecimal.ZERO;
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999.99");
    private static final BigDecimal MIN_VARIATION = new BigDecimal("-9999.99");
    private static final BigDecimal MAX_VARIATION = new BigDecimal("9999.99");
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final String baseUrl;
    private final Clock clock;

    RestMarketSourceClient(MarketSourceProperties properties, Clock clock) {
        this.baseUrl = properties.getBaseUrl();
        this.clock = Objects.requireNonNull(clock, "clock");
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
            var payload = restClient.get().uri("/indicators/{zone}", zone)
                    .retrieve().body(ProviderPayload.class);
            return indicatorsOf(zone, payload, Instant.now(clock));
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
        return indicatorsOf(zone, payload, Instant.now());
    }

    static MarketIndicators indicatorsOf(String zone, ProviderPayload payload, Instant now) {
        if (payload == null
                || payload.pricePerSquareMeter() == null
                || payload.yearOverYearVariationPercent() == null
                || payload.averageDaysOnMarket() == null
                || payload.observedAt() == null) {
            throw new MarketSourceUnavailableException("The market source returned an incomplete payload");
        }

        try {
            validateProviderValues(payload, now);
            return new MarketIndicators(zone, payload.pricePerSquareMeter(),
                    payload.yearOverYearVariationPercent(), payload.averageDaysOnMarket(),
                    payload.observedAt(), IndicatorSource.EXTERNAL_PROVIDER);
        } catch (IllegalArgumentException | ArithmeticException validationFailure) {
            throw new MarketSourceUnavailableException(
                    "The market source returned an invalid payload", validationFailure);
        }
    }

    private static void validateProviderValues(ProviderPayload payload, Instant now) {
        payload.pricePerSquareMeter().setScale(2, RoundingMode.UNNECESSARY);
        payload.yearOverYearVariationPercent().setScale(2, RoundingMode.UNNECESSARY);

        if (payload.pricePerSquareMeter().compareTo(MIN_PRICE) <= 0
                || payload.pricePerSquareMeter().compareTo(MAX_PRICE) > 0) {
            throw new IllegalArgumentException("The price per square meter is outside valid bounds");
        }

        if (payload.yearOverYearVariationPercent().compareTo(MIN_VARIATION) < 0
                || payload.yearOverYearVariationPercent().compareTo(MAX_VARIATION) > 0) {
            throw new IllegalArgumentException("The year-over-year variation percentage is outside valid bounds");
        }

        if (payload.averageDaysOnMarket() < 0) {
            throw new IllegalArgumentException("The average days on market cannot be negative");
        }

        if (now != null && payload.observedAt().isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new IllegalArgumentException("The observedAt timestamp is in the future");
        }
    }

    @Override
    public String describe() {
        return "rest";
    }

    record ProviderPayload(BigDecimal pricePerSquareMeter, BigDecimal yearOverYearVariationPercent,
            Integer averageDaysOnMarket, Instant observedAt) {
    }
}
