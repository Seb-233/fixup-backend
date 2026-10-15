package com.fixup.properties.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fixup.properties.api.GeocodingRateLimitExceededException;
import com.fixup.properties.api.GeocodingUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NominatimGeocodingProviderTest {
    private static final String BASE_URL = "https://nominatim.example.test";
    private static final String USER_AGENT = "FixUp-Test/1.0 (+https://example.test/fixup)";

    private GeocodingProperties properties;
    private MutableClock clock;
    private MockRestServiceServer server;
    private NominatimGeocodingProvider provider;

    @BeforeEach
    void setUp() {
        properties = new GeocodingProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setUserAgent(USER_AGENT);
        properties.setCacheTtl(Duration.ofMinutes(10));
        properties.setMaxCacheEntries(2);
        clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
        var builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new NominatimGeocodingProvider(builder.build(), properties, clock);
    }

    @Test
    void sendsTheRequiredNominatimQueryAndMapsOnlyPublicCandidateFields() {
        expectSearch("Carrera%207", """
                [{
                  "place_id": 987,
                  "osm_type": "way",
                  "display_name": "Carrera 7, Bogotá, Colombia",
                  "lat": "4.657000",
                  "lon": "-74.055000"
                }]
                """);

        var result = provider.geocode("Carrera 7");

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.displayName()).isEqualTo("Carrera 7, Bogotá, Colombia");
            assertThat(candidate.latitude()).isEqualByComparingTo("4.657000");
            assertThat(candidate.longitude()).isEqualByComparingTo("-74.055000");
        });
        server.verify();
    }

    @Test
    void reverseGeocodingUsesTheSameProviderAndCachesItsNormalizedResult() {
        server.expect(once(), requestTo(BASE_URL
                        + "/reverse?lat=4.657&lon=-74.055&format=jsonv2&addressdetails=1"))
                .andExpect(header(HttpHeaders.USER_AGENT, USER_AGENT))
                .andRespond(withSuccess("""
                        {"display_name":"Carrera 7, Bogota, Colombia",
                         "lat":"4.657","lon":"-74.055"}
                        """, MediaType.APPLICATION_JSON));
        var latitude = new BigDecimal("4.657");
        var longitude = new BigDecimal("-74.055");

        assertThat(provider.reverseGeocode(latitude, longitude)).singleElement()
                .satisfies(candidate -> assertThat(candidate.displayName())
                        .isEqualTo("Carrera 7, Bogota, Colombia"));
        assertThat(provider.reverseGeocode(latitude, longitude)).hasSize(1);
        server.verify();
    }

    @Test
    void reverseGeocodingReturnsNoResultOnProviderNotFound() {
        server.expect(once(), requestTo(BASE_URL
                        + "/reverse?lat=4&lon=-74&format=jsonv2&addressdetails=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(provider.reverseGeocode(new BigDecimal("4"), new BigDecimal("-74"))).isEmpty();
        server.verify();
    }

    @Test
    void returnsAndCachesAnEmptySuccessfulResponse() {
        expectSearch("Sin%20resultado", "[]");

        assertThat(provider.geocode("Sin resultado")).isEmpty();
        assertThat(provider.geocode("sin resultado")).isEmpty();

        server.verify();
    }

    @Test
    void rejectsASecondUncachedRequestInsideTheOneSecondWindow() {
        expectSearch("Primera", "[]");
        provider.geocode("Primera");

        assertThatThrownBy(() -> provider.geocode("Segunda"))
                .isInstanceOf(GeocodingRateLimitExceededException.class);
        server.verify();
    }

    @Test
    void permitsAnotherExternalRequestAfterOneSecond() {
        expectSearch("Primera", "[]");
        expectSearch("Segunda", "[]");
        provider.geocode("Primera");
        clock.advance(Duration.ofSeconds(1));

        assertThat(provider.geocode("Segunda")).isEmpty();
        server.verify();
    }

    @Test
    void convertsTimeoutAndServerErrorsToUnavailableWithoutCachingThem() {
        server.expect(once(), requestTo(searchUrl("Timeout")))
                .andRespond(withException(new IOException("read timed out")));
        server.expect(once(), requestTo(searchUrl("Error")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(searchUrl("Error")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.geocode("Timeout"))
                .isInstanceOf(GeocodingUnavailableException.class);

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> provider.geocode("Error"))
                .isInstanceOf(GeocodingUnavailableException.class);

        clock.advance(Duration.ofSeconds(1));
        assertThat(provider.geocode("Error")).isEmpty();
        server.verify();
    }

    @Test
    void mapsProviderThrottlingToThePublicRateLimitError() {
        server.expect(once(), requestTo(searchUrl("Limitada")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> provider.geocode("Limitada"))
                .isInstanceOf(GeocodingRateLimitExceededException.class);
        server.verify();
    }

    private void expectSearch(String encodedQuery, String responseBody) {
        server.expect(once(), requestTo(searchUrl(encodedQuery)))
                .andExpect(header(HttpHeaders.USER_AGENT, USER_AGENT))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    private static String searchUrl(String encodedQuery) {
        return BASE_URL + "/search?q=" + encodedQuery
                + "&format=jsonv2&limit=5&countrycodes=co&addressdetails=1";
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
