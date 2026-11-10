package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.domain.LedgerChecks;
import com.fixup.payments.domain.LedgerInvariant;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Safety: concilia el libro de pagos mientras la aplicación funciona.
 *
 * <p>Corre cada pocos minutos y bajo demanda de un administrador. Lee todo el libro en una sola
 * transacción de solo lectura con REPEATABLE READ, para que las sumas y las reglas vean la misma
 * foto aunque entren pagos mientras tanto.
 *
 * <p>La lectura corre siempre en un hilo propio, sin actor. RLS limita a cada usuario a sus
 * propias filas; una conciliación filtrada por el actor daría diferencias falsas. Es el mismo
 * tratamiento que ya tienen los demás jobs de sistema (ver RlsSessionTransactionManager). Lo que
 * devuelve son totales y hasta 20 identificadores por regla, nunca datos personales.
 */
@Service
public class ReconcileLedger {
    private static final Logger log = LoggerFactory.getLogger(ReconcileLedger.class);
    private static final int SAMPLE_LIMIT = 20;

    private final LedgerChecks checks;
    private final Clock clock;
    private final TransactionTemplate snapshot;
    private final boolean scheduled;
    private final AtomicReference<LedgerReconciliation> latest = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "ledger-reconciliation");
        thread.setDaemon(true);
        return thread;
    });

    ReconcileLedger(LedgerChecks checks, Clock clock, PlatformTransactionManager transactions,
            ObjectProvider<MeterRegistry> meters,
            @Value("${fixup.payments.reconciliation.enabled:true}") boolean scheduled) {
        this.checks = checks;
        this.clock = clock;
        this.scheduled = scheduled;
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        meters.ifAvailable(registry -> Gauge.builder("fixup.payments.ledger.violations", latest,
                        ref -> ref.get() == null ? 0 : ref.get().violations())
                .description("Filas que rompen alguna regla de conciliación en la última corrida")
                .register(registry));
    }

    @Scheduled(initialDelayString = "${fixup.payments.reconciliation.initial-delay:PT1M}",
            fixedDelayString = "${fixup.payments.reconciliation.interval:PT10M}")
    void scheduledRun() {
        if (scheduled) {
            reconcileOutsideAnyActor();
        }
    }

    /** Corre una conciliación nueva y la devuelve. Solo PLATFORM_ADMIN. */
    public LedgerReconciliation runNow(CurrentActor actor) {
        PaymentAccess.requireActiveAdmin(actor);
        return reconcileOutsideAnyActor();
    }

    private LedgerReconciliation reconcileOutsideAnyActor() {
        return CompletableFuture.supplyAsync(() -> snapshot.execute(status -> reconcile()), worker).join();
    }

    private LedgerReconciliation reconcile() {
        var findings = new ArrayList<LedgerReconciliation.Finding>();
        for (var invariant : LedgerInvariant.values()) {
            var found = checks.check(invariant, SAMPLE_LIMIT);
            if (found.count() > 0) {
                findings.add(new LedgerReconciliation.Finding(invariant.name(), invariant.description(),
                        found.count(), found.sample()));
            }
        }
        var totals = LedgerReconciliation.Totals.of(checks.totals());
        boolean balanced = totals.collected() == totals.earningsGross()
                && totals.earningsGross() == totals.commission() + totals.net()
                && totals.net() == totals.heldNet() + totals.availableNet() + totals.paidOutNet();
        var report = new LedgerReconciliation(clock.instant().truncatedTo(ChronoUnit.MILLIS),
                findings.isEmpty() && balanced, balanced, totals, findings);
        latest.set(report);
        logOutcome(report);
        return report;
    }

    private static void logOutcome(LedgerReconciliation report) {
        var totals = report.totals();
        if (report.consistent()) {
            log.info("Conciliación de pagos sin diferencias: cobrado={} comisión={} neto={} transferido={}",
                    totals.collected(), totals.commission(), totals.net(), totals.payouts());
            return;
        }
        log.warn("Conciliación de pagos con diferencias: cuadra={} cobrado={} repartido={} reglas={}",
                report.balanced(), totals.collected(), totals.earningsGross(),
                report.findings().stream().map(f -> f.code() + "=" + f.count())
                        .collect(Collectors.joining(", ")));
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
