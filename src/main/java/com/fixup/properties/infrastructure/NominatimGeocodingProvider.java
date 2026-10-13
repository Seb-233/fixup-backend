package com.fixup.properties.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fixup.properties.api.GeocodingRateLimitExceededException;
import com.fixup.properties.api.GeocodingUnavailableException;
import com.fixup.properties.application.GeocodingProvider;
import com.fixup.properties.domain.GeocodingCandidate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class NominatimGeocodingProvider implements GeocodingProvider {
    /** Public Nominatim policy permits at most one externally-started request per second. */
    static final Duration MINIMUM_REQUEST_INTERVAL = Duration.ofSeconds(1);

    private final RestClient restClient;
    private final GeocodingProperties properties;
    private final Clock clock;
    private final Object stateMonitor = new Object();
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private Instant nextRequestAt = Instant.MIN;

    NominatimGeocodingProvider(RestClient restClient, GeocodingProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<GeocodingCandidate> geocode(String normalizedQuery) {
        if (!properties.isEnabled()) {
            throw new GeocodingUnavailableException("Address geocoding is disabled");
        }

        // The query is already whitespace-normalized by the use case. Case folding completes the
        // cache key so equivalent user input does not trigger another external request.
        String cacheKey = normalizedQuery.toLowerCase(Locale.ROOT);
        Instant now = Instant.now(clock);
        synchronized (stateMonitor) {
            removeExpiredEntries(now);
            var cached = cache.get(cacheKey);
            if (cached != null) {
                return cached.candidates();
            }
            if (now.isBefore(nextRequestAt)) {
                throw new GeocodingRateLimitExceededException();
            }
            nextRequestAt = now.plus(MINIMUM_REQUEST_INTERVAL);
        }

        List<GeocodingCandidate> candidates = requestCandidates(normalizedQuery);
        cacheSuccessfulResult(cacheKey, candidates, now);
        return candidates;
    }

    private List<GeocodingCandidate> requestCandidates(String query) {
        try {
            var payload = restClient.get()
                    .uri(uri -> uri.path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "jsonv2")
                            .queryParam("limit", 5)
                            .queryParam("countrycodes", "co")
                            .queryParam("addressdetails", 1)
                            .build())
                    .retrieve()
                    .body(NominatimResult[].class);
            if (payload == null || payload.length == 0) {
                return List.of();
            }
            try {
                return java.util.Arrays.stream(payload)
                        .map(NominatimGeocodingProvider::toCandidate)
                        .toList();
            } catch (IllegalArgumentException invalidPayload) {
                throw new GeocodingUnavailableException(
                        "Nominatim returned an invalid payload", invalidPayload);
            }
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new GeocodingRateLimitExceededException();
            }
            throw new GeocodingUnavailableException("Nominatim did not answer successfully", failure);
        } catch (RestClientException failure) {
            throw new GeocodingUnavailableException("Nominatim did not answer", failure);
        }
    }

    private void cacheSuccessfulResult(String cacheKey, List<GeocodingCandidate> candidates, Instant requestedAt) {
        Duration ttl = properties.getCacheTtl();
        int maximumSize = properties.getMaxCacheEntries();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || maximumSize <= 0) {
            return;
        }
        var immutableCandidates = List.copyOf(candidates);
        synchronized (stateMonitor) {
            cache.put(cacheKey, new CacheEntry(immutableCandidates, requestedAt.plus(ttl)));
            while (cache.size() > maximumSize) {
                String eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
    }

    private void removeExpiredEntries(Instant now) {
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static GeocodingCandidate toCandidate(NominatimResult result) {
        if (result == null || result.displayName() == null || result.lat() == null || result.lon() == null) {
            throw new IllegalArgumentException("Incomplete Nominatim result");
        }
        return new GeocodingCandidate(
                result.displayName(),
                new BigDecimal(result.lat()),
                new BigDecimal(result.lon()));
    }

    private record CacheEntry(List<GeocodingCandidate> candidates, Instant expiresAt) {}

    record NominatimResult(
            @JsonProperty("display_name") String displayName,
            String lat,
            String lon) {}
}
