package com.fixup.analytics.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-15: la marca de frescura decide si un valor guardado todavía sirve como vigente. */
class FreshnessWindowTest {
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private final FreshnessWindow window = new FreshnessWindow(Duration.ofHours(6));

    @Test
    void aRecentObservationIsFresh() {
        assertThat(window.isFresh(NOW.minus(Duration.ofHours(1)), NOW)).isTrue();
    }

    @Test
    void theBoundaryIsStillFresh() {
        assertThat(window.isFresh(NOW.minus(Duration.ofHours(6)), NOW)).isTrue();
    }

    @Test
    void oneSecondPastTheWindowIsNoLongerFresh() {
        assertThat(window.isFresh(NOW.minus(Duration.ofHours(6)).minusSeconds(1), NOW)).isFalse();
    }

    @Test
    void aNonPositiveWindowIsRejected() {
        assertThatThrownBy(() -> new FreshnessWindow(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreshnessWindow(Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreshnessWindow(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
