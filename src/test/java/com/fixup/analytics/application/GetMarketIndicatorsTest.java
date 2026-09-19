package com.fixup.analytics.application;

import com.fixup.analytics.api.IndicatorFreshness;
import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.api.MarketIndicatorsUnavailableException;
import com.fixup.analytics.domain.FreshnessWindow;
import com.fixup.analytics.domain.MarketIndicatorSnapshots;
import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketIndicatorsSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-UC-15: táctica de recuperación "Graceful degradation (degradación elegante)" del 25-ago.
 * El servicio sigue respondiendo con capacidad reducida y lo dice.
 */
class GetMarketIndicatorsTest {

    private static final CurrentActor ACTOR = new CurrentActor(UUID.randomUUID(), "auth0|reader",
            Set.of(Role.TENANT), UserStatus.ACTIVE);

    private static MarketIndicators indicators(Instant observedAt, long price) {
        return indicators(observedAt, price, IndicatorSource.EXTERNAL_PROVIDER);
    }

    private static MarketIndicators indicators(Instant observedAt, long price, IndicatorSource source) {
        return new MarketIndicators("CHAPINERO", BigDecimal.valueOf(price), BigDecimal.valueOf(2.5),
                40, observedAt, source);
    }

    private static final class InMemorySnapshots implements MarketIndicatorSnapshots {
        private final Map<String, MarketIndicators> rows = new HashMap<>();

        @Override
        public Optional<MarketIndicators> findByZone(String zone) {
            return Optional.ofNullable(rows.get(zone));
        }

        @Override
        public boolean saveIfNewer(MarketIndicators value) {
            var existing = rows.get(value.zone());
            if (existing != null && value.observedAt().isBefore(existing.observedAt())) {
                return false;
            }
            rows.put(value.zone(), value);
            return true;
        }

        @Override
        public void save(MarketIndicators value) {
            saveIfNewer(value);
        }
    }

    private static final class FailingSource implements MarketIndicatorsSource {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public MarketIndicators fetch(String zone) {
            calls.incrementAndGet();
            throw new MarketSourceUnavailableException("synthetic outage");
        }
    }

    private GetMarketIndicators useCase(MarketIndicatorsSource source, MarketIndicatorSnapshots snapshots) {
        return new GetMarketIndicators(source, snapshots, new FreshnessWindow(Duration.ofHours(6)));
    }

    @Test
    void aSuspendedActorIsForbiddenToReadIndicatorsAndSourceIsNotCalled() {
        var suspendedActor = new CurrentActor(UUID.randomUUID(), "auth0|suspended",
                Set.of(Role.TENANT), UserStatus.SUSPENDED);
        var source = new FailingSource();
        var snapshots = new InMemorySnapshots();

        assertThatThrownBy(() -> useCase(source, snapshots).execute(suspendedActor, "CHAPINERO"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Active account required");
        assertThat(source.calls).hasValue(0);
    }

    @Test
    void aDisabledActorIsForbiddenToReadIndicatorsAndSourceIsNotCalled() {
        var disabledActor = new CurrentActor(UUID.randomUUID(), "auth0|disabled",
                Set.of(Role.TENANT), UserStatus.DISABLED);
        var source = new FailingSource();
        var snapshots = new InMemorySnapshots();

        assertThatThrownBy(() -> useCase(source, snapshots).execute(disabledActor, "CHAPINERO"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(source.calls).hasValue(0);
    }

    @Test
    void aNullActorThrowsAccessDenied() {
        var source = new FailingSource();
        var snapshots = new InMemorySnapshots();

        assertThatThrownBy(() -> useCase(source, snapshots).execute(null, "CHAPINERO"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(source.calls).hasValue(0);
    }

    @Test
    void aLiveAnswerIsMarkedLiveAndIsCached() {
        var snapshots = new InMemorySnapshots();
        var live = indicators(Instant.now(), 5_000_000);

        var view = useCase(zone -> live, snapshots).execute(ACTOR, "chapinero");

        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.LIVE);
        assertThat(view.degraded()).isFalse();
        assertThat(view.zone()).isEqualTo("CHAPINERO");
        assertThat(snapshots.findByZone("CHAPINERO")).contains(live);
    }

    @Test
    void aFreshCacheAnswersWithoutTouchingTheExternalSource() {
        var snapshots = new InMemorySnapshots();
        snapshots.save(indicators(Instant.now().minus(Duration.ofHours(1)), 4_000_000));
        var source = new FailingSource();

        var view = useCase(source, snapshots).execute(ACTOR, "CHAPINERO");

        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.CACHED);
        assertThat(view.degraded()).isFalse();
        // Menos dependencia del tercero: dentro de la ventana ni siquiera se le consulta.
        assertThat(source.calls).hasValue(0);
    }

    @Test
    void anUnavailableSourceDegradesToTheLastKnownValueAndSaysSo() {
        var snapshots = new InMemorySnapshots();
        var stale = indicators(Instant.now().minus(Duration.ofDays(3)), 3_500_000);
        snapshots.save(stale);

        var view = useCase(new FailingSource(), snapshots).execute(ACTOR, "CHAPINERO");

        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.DEGRADED);
        assertThat(view.degraded()).isTrue();
        assertThat(view.pricePerSquareMeter()).isEqualTo(stale.pricePerSquareMeter());
        // El dato degradado conserva la fecha real de observación: no se presenta como actual.
        assertThat(view.observedAt()).isEqualTo(stale.observedAt());
    }

    @Test
    void ifConcurrentSaveRejectsOlderDataTheExistingCacheIsReturnedAsCached() {
        var snapshots = new InMemorySnapshots();
        var newer = indicators(Instant.now(), 6_000_000);
        snapshots.save(newer);

        var older = indicators(Instant.now().minus(Duration.ofHours(1)), 5_000_000);
        var view = useCase(zone -> older, snapshots).execute(ACTOR, "CHAPINERO");

        assertThat(view.pricePerSquareMeter()).isEqualTo(newer.pricePerSquareMeter());
        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.CACHED);
    }

    /** LIVE dice cómo se obtuvo el dato, source dice de quién: son cosas distintas y viajan ambas. */
    @Test
    void aSyntheticValueIsReportedAsSyntheticEvenWhenItIsLive() {
        var snapshots = new InMemorySnapshots();
        var synthetic = indicators(Instant.now(), 4_800_000, IndicatorSource.DEVELOPMENT_SYNTHETIC);

        var view = useCase(zone -> synthetic, snapshots).execute(ACTOR, "CHAPINERO");

        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.LIVE);
        assertThat(view.source()).isEqualTo(IndicatorSource.DEVELOPMENT_SYNTHETIC);
        assertThat(view.synthetic()).isTrue();
    }

    @Test
    void aDegradedAnswerKeepsTheProvenanceOfTheValueItServes() {
        var snapshots = new InMemorySnapshots();
        snapshots.save(indicators(Instant.now().minus(Duration.ofDays(3)), 3_500_000,
                IndicatorSource.DEVELOPMENT_SYNTHETIC));

        var view = useCase(new FailingSource(), snapshots).execute(ACTOR, "CHAPINERO");

        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.DEGRADED);
        assertThat(view.source()).isEqualTo(IndicatorSource.DEVELOPMENT_SYNTHETIC);
        assertThat(view.synthetic()).isTrue();
    }

    @Test
    void aValueFromTheExternalProviderIsNotReportedAsSynthetic() {
        var view = useCase(zone -> indicators(Instant.now(), 5_000_000), new InMemorySnapshots())
                .execute(ACTOR, "CHAPINERO");

        assertThat(view.source()).isEqualTo(IndicatorSource.EXTERNAL_PROVIDER);
        assertThat(view.synthetic()).isFalse();
    }

    @Test
    void anUnavailableSourceWithoutAnyKnownValueDoesNotInventData() {
        assertThatThrownBy(() -> useCase(new FailingSource(), new InMemorySnapshots()).execute(ACTOR, "NUEVA"))
                .isInstanceOf(MarketIndicatorsUnavailableException.class)
                .hasMessageContaining("NUEVA");
    }

    @Test
    void aStaleCacheIsRefreshedWhenTheSourceAnswersAgain() {
        var snapshots = new InMemorySnapshots();
        snapshots.save(indicators(Instant.now().minus(Duration.ofDays(2)), 3_000_000));
        var recovered = indicators(Instant.now(), 6_000_000);

        var view = useCase(zone -> recovered, snapshots).execute(ACTOR, "CHAPINERO");

        assertThat(view.freshness()).isEqualTo(IndicatorFreshness.LIVE);
        assertThat(snapshots.findByZone("CHAPINERO")).contains(recovered);
    }

    @Test
    void theZoneIsNormalisedSoTheCacheDoesNotFragment() {
        var snapshots = new InMemorySnapshots();
        snapshots.save(indicators(Instant.now().minus(Duration.ofHours(1)), 4_200_000));

        for (String requested : new String[]{"chapinero", "  Chapinero  ", "CHAPINERO"}) {
            assertThat(useCase(new FailingSource(), snapshots).execute(ACTOR, requested).freshness())
                    .isEqualTo(IndicatorFreshness.CACHED);
        }
    }

    @Test
    void anEmptyZoneIsRejectedBeforeAnyLookup() {
        var source = new FailingSource();

        assertThatThrownBy(() -> useCase(source, new InMemorySnapshots()).execute(ACTOR, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(source.calls).hasValue(0);
    }
}
