package com.fixup.analytics.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del adaptador de la fuente externa. Los valores por defecto viven aquí y no en
 * application.yml para no tocar configuración compartida por todo el equipo.
 */
@ConfigurationProperties("fixup.analytics.market-source")
public class MarketSourceProperties {
    /**
     * Selecciona el adaptador y no tiene valor implícito: hay que declararla. {@code rest} habilita
     * el adaptador HTTP; {@code development} habilita el respaldo sintético y además exige el perfil
     * {@code dev}. Si no se declara no se registra ninguna fuente y el caso de uso informa
     * indisponibilidad, porque faltar configuración no puede terminar en datos inventados
     * presentados como reales.
     */
    private Provider provider = Provider.REST;

    /** Base del proveedor comercial cuando exista uno contratado. */
    private String baseUrl = "";

    /** Táctica de detección de defectos: Timeout. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(3);

    /**
     * Táctica de recuperación: Retry. Máximo número de reintentos tras el primer intento. Pocos a
     * propósito: cada reintento hace esperar al hilo que atiende la petición.
     * {@link TimeoutAndRetryMarketSource#MAX_RETRIES} lo recorta si se configura de más.
     */
    private int maxRetries = 2;

    /**
     * Frecuencia de reintentos. Corta por la misma razón;
     * {@link TimeoutAndRetryMarketSource#MAX_RETRY_DELAY} la recorta si se configura de más.
     */
    private Duration retryDelay = Duration.ofMillis(200);

    /** Marca de frescura de la caché por zona. */
    private Duration freshness = Duration.ofHours(6);

    public enum Provider {
        DEVELOPMENT, REST
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Duration getFreshness() {
        return freshness;
    }

    public void setFreshness(Duration freshness) {
        this.freshness = freshness;
    }
}
