package com.fixup.integration;

import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketIndicators;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, MarketIndicatorsHttpContract.ControllableSourceConfiguration.class})
@Testcontainers
class PostgresMarketIndicatorsIT extends MarketIndicatorsHttpContract {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentInitialQueriesForSameZoneSucceedWithout500AndLeaveSingleRow() throws Exception {
        bootstrap("auth0|concurrent-reader-1");
        bootstrap("auth0|concurrent-reader-2");

        var zone = "CONCURRENT-ZONE";
        var barrier = new CyclicBarrier(2);
        ControllableSource.BARRIER.set(barrier);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var future1 = executor.submit(() -> read("auth0|concurrent-reader-1", zone));
            var future2 = executor.submit(() -> read("auth0|concurrent-reader-2", zone));

            var res1 = future1.get(10, TimeUnit.SECONDS);
            var res2 = future2.get(10, TimeUnit.SECONDS);

            res1.andExpect(status().isOk());
            res2.andExpect(status().isOk());

            var body1 = mapper.readTree(res1.andReturn().getResponse().getContentAsString());
            var body2 = mapper.readTree(res2.andReturn().getResponse().getContentAsString());

            assertThat(List.of("LIVE", "CACHED")).contains(body1.get("freshness").asText());
            assertThat(List.of("LIVE", "CACHED")).contains(body2.get("freshness").asText());

            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM market_indicator_snapshots WHERE zone = ?",
                    Integer.class, zone);
            assertThat(count).isEqualTo(1);
        } finally {
            ControllableSource.BARRIER.set(null);
            executor.shutdownNow();
        }
    }

    @Test
    void moreRecentObservedAtTakesPrecedenceOverOlderInPostgres() {
        var zone = "PG-MONOTONIC-TEST";
        var t2 = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        var t1 = t2.minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS);

        var newer = new MarketIndicators(zone, BigDecimal.valueOf(7_500_000),
                BigDecimal.valueOf(5.0), 35, t2, IndicatorSource.EXTERNAL_PROVIDER);
        boolean acceptedNewer = snapshots.saveIfNewer(newer);
        assertThat(acceptedNewer).isTrue();

        var cachedAtBefore = jdbc.queryForObject(
                "SELECT cached_at FROM market_indicator_snapshots WHERE zone = ?",
                Timestamp.class, zone);

        var older = new MarketIndicators(zone, BigDecimal.valueOf(4_000_000),
                BigDecimal.valueOf(1.0), 80, t1, IndicatorSource.DEVELOPMENT_SYNTHETIC);
        boolean acceptedOlder = snapshots.saveIfNewer(older);
        assertThat(acceptedOlder).isFalse();

        var stored = snapshots.findByZone(zone).orElseThrow();
        assertThat(stored.observedAt()).isEqualTo(t2);
        assertThat(stored.pricePerSquareMeter()).isEqualByComparingTo(BigDecimal.valueOf(7_500_000));
        assertThat(stored.source()).isEqualTo(IndicatorSource.EXTERNAL_PROVIDER);

        var cachedAtAfter = jdbc.queryForObject(
                "SELECT cached_at FROM market_indicator_snapshots WHERE zone = ?",
                Timestamp.class, zone);
        assertThat(cachedAtAfter).isEqualTo(cachedAtBefore);
    }
}
