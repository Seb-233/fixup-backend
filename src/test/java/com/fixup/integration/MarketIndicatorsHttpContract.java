package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketIndicatorsSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reused unchanged against H2 and PostgreSQL to keep the HTTP/security contract equivalent. */
abstract class MarketIndicatorsHttpContract {

    /** Fuente externa controlable: permite provocar una caída real, no simulada con mocks HTTP. */
    static final class ControllableSource implements MarketIndicatorsSource {
        static final AtomicBoolean DOWN = new AtomicBoolean(false);
        static final AtomicInteger CALLS = new AtomicInteger();
        static final AtomicReference<Instant> OBSERVED_AT =
                new AtomicReference<>(Instant.parse("2026-09-18T09:00:00Z"));

        @Override
        public MarketIndicators fetch(String zone) {
            CALLS.incrementAndGet();
            if (DOWN.get()) {
                throw new MarketSourceUnavailableException("synthetic outage");
            }
            return new MarketIndicators(zone, BigDecimal.valueOf(5_250_000), BigDecimal.valueOf(3.40),
                    52, OBSERVED_AT.get());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControllableSourceConfiguration {
        @Bean
        @Primary
        MarketIndicatorsSource controllableMarketSource() {
            return new ControllableSource();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM market_indicator_snapshots");
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        ControllableSource.DOWN.set(false);
        ControllableSource.CALLS.set(0);
        ControllableSource.OBSERVED_AT.set(Instant.now().minus(Duration.ofMinutes(30)));
    }

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", "reader@example.test")
                .claim("name", "Synthetic reader"));
    }

    private void bootstrap(String subject) throws Exception {
        mvc.perform(post("/auth/bootstrap").with(identity(subject))).andExpect(status().isCreated());
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TENANT\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions read(String subject, String zone)
            throws Exception {
        return mvc.perform(get("/analytics/zones/" + zone + "/market-indicators").with(identity(subject)));
    }

    @Test
    void anonymousIndicatorRequestsReturnUniform401() throws Exception {
        mvc.perform(get("/analytics/zones/CHAPINERO/market-indicators"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theFirstReadGoesToTheSourceAndIsReportedAsLive() throws Exception {
        bootstrap("auth0|market-reader");

        read("auth0|market-reader", "CHAPINERO").andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("CHAPINERO"))
                .andExpect(jsonPath("$.pricePerSquareMeter").value(5250000))
                .andExpect(jsonPath("$.yearOverYearVariationPercent").value(3.40))
                .andExpect(jsonPath("$.averageDaysOnMarket").value(52))
                .andExpect(jsonPath("$.freshness").value("LIVE"))
                .andExpect(jsonPath("$.degraded").value(false));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_indicator_snapshots", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aSecondReadInsideTheFreshnessWindowIsServedFromTheCache() throws Exception {
        bootstrap("auth0|market-reader");
        read("auth0|market-reader", "CHAPINERO").andExpect(jsonPath("$.freshness").value("LIVE"));

        read("auth0|market-reader", "CHAPINERO").andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("CACHED"))
                .andExpect(jsonPath("$.degraded").value(false));

        // La fuente externa se consultó una sola vez: dentro de la ventana no se la molesta.
        assertThat(ControllableSource.CALLS).hasValue(1);
    }

    @Test
    void anUnavailableSourceDegradesToTheLastKnownValueAndSaysSoToTheClient() throws Exception {
        bootstrap("auth0|market-reader");
        var observed = Instant.now().minus(Duration.ofDays(4));
        ControllableSource.OBSERVED_AT.set(observed);
        read("auth0|market-reader", "CHAPINERO").andExpect(jsonPath("$.freshness").value("LIVE"));

        ControllableSource.DOWN.set(true);

        var response = read("auth0|market-reader", "CHAPINERO").andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("DEGRADED"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.pricePerSquareMeter").value(5250000))
                .andReturn();

        // El dato degradado conserva su fecha real de observación: no se presenta como actual.
        var observedAt = Instant.parse(mapper.readTree(response.getResponse().getContentAsString())
                .get("observedAt").asText());
        assertThat(observedAt).isCloseTo(observed, within(2000));
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long millis) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(millis,
                java.time.temporal.ChronoUnit.MILLIS);
    }

    @Test
    void anUnavailableSourceWithNoKnownValueAnswers503AndNeverInventsData() throws Exception {
        bootstrap("auth0|market-reader");
        ControllableSource.DOWN.set(true);

        read("auth0|market-reader", "ZONA-SIN-HISTORIA")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INDICATORS_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_indicator_snapshots", Integer.class))
                .isZero();
    }

    @Test
    void theCachedRowKeepsTheObservationInstantAsItsFreshnessMark() throws Exception {
        bootstrap("auth0|market-reader");
        var observed = Instant.now().minus(Duration.ofHours(2));
        ControllableSource.OBSERVED_AT.set(observed);

        read("auth0|market-reader", "USAQUEN").andExpect(status().isOk());

        var row = jdbc.queryForList("SELECT * FROM market_indicator_snapshots").get(0);
        var columns = row.keySet().stream().map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList();
        assertThat(columns).contains("observed_at", "cached_at");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_indicator_snapshots WHERE zone = ?",
                Integer.class, "USAQUEN")).isEqualTo(1);
    }

    @Test
    void theZoneIsNormalisedSoTheCacheDoesNotFragment() throws Exception {
        bootstrap("auth0|market-reader");

        read("auth0|market-reader", "chapinero").andExpect(jsonPath("$.zone").value("CHAPINERO"));
        read("auth0|market-reader", "Chapinero").andExpect(jsonPath("$.freshness").value("CACHED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_indicator_snapshots", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aSuspendedAccountCannotReadIndicators() throws Exception {
        bootstrap("auth0|suspended-reader");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE auth0_subject = ?", "auth0|suspended-reader");

        read("auth0|suspended-reader", "CHAPINERO").andExpect(status().isForbidden());
    }

    @Test
    void aZoneLongerThanTheLimitIsRejected() throws Exception {
        bootstrap("auth0|market-reader");

        read("auth0|market-reader", "Z".repeat(65)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
