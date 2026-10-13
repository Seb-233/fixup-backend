package com.fixup.properties.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("fixup.geocoding")
@Validated
public class GeocodingProperties {
    private boolean enabled = true;

    @NotBlank
    private String baseUrl = "https://nominatim.openstreetmap.org";

    @NotBlank
    private String userAgent = "FixUp/1.0 (+https://github.com/Seb-233/fixup-backend)";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(3);

    /** Successful results, including an empty list, remain cached for this duration. */
    @NotNull
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** Maximum number of normalized, case-insensitive address keys retained in local memory. */
    @Positive
    private int maxCacheEntries = 256;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
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

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public int getMaxCacheEntries() {
        return maxCacheEntries;
    }

    public void setMaxCacheEntries(int maxCacheEntries) {
        this.maxCacheEntries = maxCacheEntries;
    }

    @AssertTrue(message = "connect-timeout, read-timeout and cache-ttl must be positive")
    public boolean isDurationConfigurationValid() {
        return isPositive(connectTimeout) && isPositive(readTimeout) && isPositive(cacheTtl);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
