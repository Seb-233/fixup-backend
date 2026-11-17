package com.fixup.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.LedgerReconciliationUnavailableException;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.domain.LedgerChecks;
import com.fixup.payments.domain.LedgerInvariant;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Límites de operación de la conciliación, sin base de datos. */
class ReconcileLedgerTest {
    private static final CurrentActor ADMIN = new CurrentActor(UUID.randomUUID(), "auth0|admin",
            Set.of(Role.PLATFORM_ADMIN), UserStatus.ACTIVE);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeLedger ledger = new FakeLedger();
    private ReconcileLedger reconcile;

    @AfterEach
    void stop() {
        if (reconcile != null) {
            reconcile.stop();
        }
    }

    @Test
    void aBalancedLedgerIsConsistentAndListsEveryEquation() {
        var report = service(true, Duration.ofSeconds(5)).runNow(ADMIN);

        assertThat(report.consistent()).isTrue();
        assertThat(report.balances()).hasSize(10).allMatch(LedgerReconciliation.Balance::holds);
        assertThat(report.scanned().payments()).isEqualTo(3);
        assertThat(runs("consistent")).isEqualTo(1);
        assertThat(gauge("fixup.payments.ledger.violations")).isZero();
    }

    @Test
    void sumsThatDoNotAddUpAreInconsistentEvenWithoutFindings() {
        ledger.totals = sums(1_000, 900);

        var report = service(true, Duration.ofSeconds(5)).runNow(ADMIN);

        assertThat(report.findings()).isEmpty();
        assertThat(report.balanced()).isFalse();
        assertThat(report.consistent()).isFalse();
        assertThat(report.balances()).filteredOn(balance -> !balance.holds())
                .extracting(LedgerReconciliation.Balance::code)
                .contains("SERVICE_COLLECTED_EQUALS_SERVICE_EARNINGS");
        assertThat(runs("inconsistent")).isEqualTo(1);
        assertThat(gauge("fixup.payments.ledger.violations")).isPositive();
    }

    @Test
    void aLedgerThatCannotBeReadHasNoResult() {
        ledger.failure = new IllegalStateException("permission denied for table fixer_earnings");
        var service = service(true, Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.runNow(ADMIN))
                .isInstanceOf(LedgerReconciliationUnavailableException.class)
                .extracting(failure -> ((LedgerReconciliationUnavailableException) failure).code())
                .isEqualTo("LEDGER_UNREADABLE");
        assertThat(gauge("fixup.payments.ledger.unreadable")).isEqualTo(1);
        assertThat(runs("unreadable")).isEqualTo(1);

        // La corrida programada no revienta el planificador y deja la misma señal.
        service.scheduledRun();
        assertThat(runs("unreadable")).isEqualTo(2);

        ledger.failure = null;
        assertThat(service.runNow(ADMIN).consistent()).isTrue();
        assertThat(gauge("fixup.payments.ledger.unreadable")).isZero();
    }

    @Test
    void whenAnotherReplicaHasTheTurnTheScheduledRunSkipsAndTheManualOneAsksToRetry() {
        ledger.myTurn = false;
        var service = service(true, Duration.ofSeconds(5));

        service.scheduledRun();
        assertThat(runs("skipped")).isEqualTo(1);
        assertThat(ledger.scans.get()).isZero();

        assertThatThrownBy(() -> service.runNow(ADMIN))
                .isInstanceOf(LedgerReconciliationUnavailableException.class)
                .extracting(failure -> ((LedgerReconciliationUnavailableException) failure).code())
                .isEqualTo("LEDGER_RECONCILIATION_BUSY");
        assertThat(ledger.scans.get()).isZero();
    }

    @Test
    void requestsThatArriveDuringARunShareItInsteadOfScanningAgain() throws Exception {
        ledger.hold = new CountDownLatch(1);
        var service = service(true, Duration.ofSeconds(5));

        var first = CompletableFuture.supplyAsync(() -> service.runNow(ADMIN));
        assertThat(ledger.started.await(5, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> service.runNow(ADMIN));
        var third = CompletableFuture.supplyAsync(() -> service.runNow(ADMIN));
        Thread.sleep(200);
        ledger.hold.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(second.get(5, TimeUnit.SECONDS))
                .isSameAs(third.get(5, TimeUnit.SECONDS));
        assertThat(ledger.scans.get()).isEqualTo(1);
    }

    @Test
    void aRunThatOutlastsItsTimeLimitHasNoResult() {
        ledger.hold = new CountDownLatch(1);
        var service = service(true, Duration.ofMillis(100), 0);
        try {
            assertThatThrownBy(() -> service.runNow(ADMIN))
                    .isInstanceOf(LedgerReconciliationUnavailableException.class)
                    .extracting(failure -> ((LedgerReconciliationUnavailableException) failure).code())
                    .isEqualTo("LEDGER_UNREADABLE");
        } finally {
            ledger.hold.countDown();
        }
    }

    @Test
    void onlyAnActivePlatformAdminRunsItAndTheScheduleCanBeTurnedOff() {
        var service = service(false, Duration.ofSeconds(5));
        var owner = new CurrentActor(UUID.randomUUID(), "auth0|owner", Set.of(Role.OWNER), UserStatus.ACTIVE);
        var suspended = new CurrentActor(UUID.randomUUID(), "auth0|old-admin", Set.of(Role.PLATFORM_ADMIN),
                UserStatus.SUSPENDED);

        assertThatThrownBy(() -> service.runNow(owner)).isInstanceOf(PaymentAccessDeniedException.class);
        assertThatThrownBy(() -> service.runNow(suspended)).isInstanceOf(PaymentAccessDeniedException.class);
        service.scheduledRun();

        assertThat(ledger.scans.get()).isZero();
    }

    @Test
    void turnedOffItAnswersDisabledWithoutReadingTheLedgerOrPublishingMeters() {
        var service = service(false, Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.runNow(ADMIN))
                .isInstanceOf(LedgerReconciliationUnavailableException.class)
                .extracting(failure -> ((LedgerReconciliationUnavailableException) failure).code())
                .isEqualTo("LEDGER_RECONCILIATION_DISABLED");
        service.preflightWhenEnabled();

        assertThat(ledger.scans.get()).isZero();
        assertThat(ledger.verifications.get()).isZero();
        // Sin corridas no hay "0 violaciones" que se lea como "cuadra".
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void turnedOnWithoutTheReaderTheStartupPreflightBlocks() {
        ledger.failure = new IllegalStateException("permission denied to set role \"fixup_ledger_auditor\"");
        var service = service(true, Duration.ofSeconds(5));

        assertThatThrownBy(service::preflightWhenEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scripts/provision-ledger-reader.sql")
                .hasRootCauseMessage("permission denied to set role \"fixup_ledger_auditor\"");
        assertThat(ledger.scans.get()).isZero();

        ledger.failure = null;
        service.preflightWhenEnabled();
        assertThat(ledger.verifications.get()).isEqualTo(2);
        assertThat(ledger.scans.get()).isZero();
    }

    /** Logs y métricas: solo códigos, conteos y un desenlace de un conjunto cerrado. */
    @Test
    void logsAndMetersCarryNoAmountsNorRowIdentifiers() {
        UUID row = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
        var logger = (Logger) LoggerFactory.getLogger(ReconcileLedger.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var service = service(true, Duration.ofSeconds(5));
            ledger.totals = sums(1_234_567, 1_234_567);
            assertThat(service.runNow(ADMIN).consistent()).isTrue();
            ledger.totals = sums(7_654_321, 7_650_000);
            ledger.finding = new LedgerChecks.Finding(1, List.of(row));
            var report = service.runNow(ADMIN);
            assertThat(report.consistent()).isFalse();
            assertThat(report.findings().get(0).sample()).containsExactly(row);
        } finally {
            logger.detachAppender(captured);
        }

        assertThat(captured.list).hasSize(2);
        assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(row.toString(), "1234567", "7654321", "7650000", "4321", "≠"))
                .last().asString().contains("SERVICE_COLLECTED_EQUALS_SERVICE_EARNINGS")
                .contains(LedgerInvariant.values()[0].name() + "=1");
        assertThat(meters.getMeters()).extracting(meter -> meter.getId().getName())
                .allMatch(name -> name.startsWith("fixup.payments.ledger."));
        assertThat(meters.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getKey).containsOnly("outcome");
        assertThat(meters.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getValue).isSubsetOf("consistent", "inconsistent", "skipped", "unreadable");
        assertThat(meters.getMeters()).extracting(Meter::getId).extracting(Meter.Id::getType)
                .doesNotContain(Meter.Type.DISTRIBUTION_SUMMARY);
    }

    private ReconcileLedger service(boolean scheduled, Duration timeout) {
        return service(scheduled, timeout, 5_000);
    }

    private ReconcileLedger service(boolean scheduled, Duration timeout, long graceMillis) {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new SimpleTransactionStatus());
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meters);
        reconcile = new ReconcileLedger(ledger, Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC),
                transactions, provider, scheduled, timeout, graceMillis);
        return reconcile;
    }

    private double runs(String outcome) {
        var counter = meters.find("fixup.payments.ledger.reconciliation.runs").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private static LedgerChecks.Totals sums(long serviceCollected, long serviceEarnings) {
        long commission = serviceEarnings / 10;
        return new LedgerChecks.Totals(serviceCollected, 0, serviceEarnings, serviceEarnings, 0, 0, commission,
                serviceEarnings - commission, serviceEarnings - commission, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static final class FakeLedger implements LedgerChecks {
        boolean myTurn = true;
        RuntimeException failure;
        CountDownLatch hold;
        Totals totals = sums(1_000, 1_000);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger scans = new AtomicInteger();
        final AtomicInteger verifications = new AtomicInteger();
        Finding finding;

        @Override
        public boolean openSystemRead(Duration statementTimeout) {
            if (failure != null) {
                throw failure;
            }
            return myTurn;
        }

        @Override
        public void verifySystemRead() {
            verifications.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public Finding check(LedgerInvariant invariant, int sampleLimit) {
            // Un solo hallazgo, en la primera regla, cuando la prueba lo pide.
            if (finding != null && invariant == LedgerInvariant.values()[0]) {
                return finding;
            }
            return new Finding(0, List.of());
        }

        @Override
        public Totals totals() {
            scans.incrementAndGet();
            started.countDown();
            if (hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return totals;
        }

        @Override
        public Scanned scanned() {
            return new Scanned(3, 3, 3, 0, 0, 0, 0);
        }
    }
}
