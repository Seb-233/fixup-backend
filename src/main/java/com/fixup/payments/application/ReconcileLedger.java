package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.LedgerReconciliationUnavailableException;
import com.fixup.payments.domain.LedgerChecks;
import com.fixup.payments.domain.LedgerInvariant;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
 * <p>La lectura corre en un hilo propio, sin actor, y dentro de la transacción pasa al rol lector
 * fixup_ledger_auditor (V48): RLS limita a cada usuario a sus propias filas y, sin actor, un rol
 * restringido no ve ninguna, así que la conciliación no puede depender de quién la pide ni de con
 * qué usuario se conecta la aplicación. Devuelve totales y hasta 20 identificadores por regla,
 * nunca datos personales.
 *
 * <p>Límites de operación:
 * <ul>
 *   <li>Una sola corrida a la vez por instancia: las peticiones que llegan mientras hay una en curso
 *       reciben el resultado de esa.</li>
 *   <li>Una sola corrida a la vez entre réplicas (turno con bloqueo de PostgreSQL): la programada se
 *       salta su ciclo si otra instancia ya concilia, y la manual responde 503 para reintentar.</li>
 *   <li>Cada corrida tiene un tiempo máximo. Si se agota o la lectura falla, no hay resultado: 503 y
 *       contador de fallos, nunca "sin diferencias".</li>
 * </ul>
 *
 * <p>Viene apagada ({@code fixup.payments.reconciliation.enabled=false}): ni corrida programada, ni
 * métricas, y la consulta manual responde 503 LEDGER_RECONCILIATION_DISABLED. Solo se enciende
 * después de aprovisionar el lector con scripts/provision-ledger-reader.sql. Encendida, el arranque
 * verifica antes que el runtime pueda leer el libro como ese rol y, si no puede, no arranca (ver
 * {@link #preflight()}).
 *
 * <p>Detecta descuadres; no los corrige ni impide por sí sola que ocurran.
 */
@Service
public class ReconcileLedger {
    private static final Logger log = LoggerFactory.getLogger(ReconcileLedger.class);
    private static final int SAMPLE_LIMIT = 20;
    private static final String RUNS = "fixup.payments.ledger.reconciliation.runs";
    public static final String DISABLED = "LEDGER_RECONCILIATION_DISABLED";

    private final LedgerChecks checks;
    private final Clock clock;
    private final TransactionTemplate snapshot;
    private final boolean enabled;
    private final Duration timeout;
    private final long graceMillis;
    private final MeterRegistry meters;
    private final AtomicReference<LedgerReconciliation> latest = new AtomicReference<>();
    /** 1 si la última corrida no pudo leer el libro; para alertar aunque no haya hallazgos. */
    private final AtomicInteger unreadable = new AtomicInteger();
    private final Object turn = new Object();
    private CompletableFuture<Optional<LedgerReconciliation>> running;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "ledger-reconciliation");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    ReconcileLedger(LedgerChecks checks, Clock clock, PlatformTransactionManager transactions,
            ObjectProvider<MeterRegistry> meters,
            @Value("${fixup.payments.reconciliation.enabled:false}") boolean enabled,
            @Value("${fixup.payments.reconciliation.timeout:PT30S}") Duration timeout) {
        this(checks, clock, transactions, meters, enabled, timeout, 5_000);
    }

    /** {@code graceMillis}: margen sobre el tiempo máximo para recibir el error de la transacción. */
    ReconcileLedger(LedgerChecks checks, Clock clock, PlatformTransactionManager transactions,
            ObjectProvider<MeterRegistry> meters, boolean enabled, Duration timeout, long graceMillis) {
        this.graceMillis = graceMillis;
        this.checks = checks;
        this.clock = clock;
        this.enabled = enabled;
        this.timeout = timeout;
        this.meters = meters.getIfAvailable();
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(Math.toIntExact(Math.max(1, timeout.toSeconds())));
        // Apagada no publica medidores: un "0 violaciones" sin corridas se leería como "cuadra".
        if (this.meters != null && enabled) {
            Gauge.builder("fixup.payments.ledger.violations", latest,
                            ref -> ref.get() == null ? 0 : ref.get().violations())
                    .description("Filas y ecuaciones que no cuadran en la última conciliación que leyó el libro")
                    .register(this.meters);
            Gauge.builder("fixup.payments.ledger.unreadable", unreadable, AtomicInteger::get)
                    .description("1 si la última conciliación no pudo leer el libro")
                    .register(this.meters);
        }
    }

    /** Con la corrida programada encendida, un runtime sin aprovisionar no llega a recibir tráfico. */
    @EventListener(ApplicationReadyEvent.class)
    void preflightWhenEnabled() {
        if (enabled) {
            preflight();
        }
    }

    /**
     * Preflight bloqueante: comprueba, sin leer filas ni tomar el turno entre réplicas, que el
     * runtime puede asumir fixup_ledger_auditor y que ese rol tiene todas las columnas que usan las
     * reglas.
     *
     * @throws IllegalStateException si falta el aprovisionamiento o algún permiso
     */
    public void preflight() {
        try {
            snapshot.executeWithoutResult(status -> checks.verifySystemRead());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("SAF-01 preflight failed: the runtime cannot read the payment ledger as "
                    + "fixup_ledger_auditor. Run scripts/provision-ledger-reader.sql for this runtime login "
                    + "(docs/saf-01-conciliacion-pagos.md) or keep fixup.payments.reconciliation.enabled=false",
                    failure);
        }
    }

    @Scheduled(initialDelayString = "${fixup.payments.reconciliation.initial-delay:PT1M}",
            fixedDelayString = "${fixup.payments.reconciliation.interval:PT10M}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            if (await(submit()).isEmpty()) {
                log.info("Conciliación de pagos omitida: otra instancia ya está conciliando");
            }
        } catch (LedgerReconciliationUnavailableException failure) {
            // Ya quedó registrado; el siguiente ciclo vuelve a intentar.
        }
    }

    /**
     * Corre una conciliación nueva y la devuelve. Solo PLATFORM_ADMIN.
     *
     * @throws LedgerReconciliationUnavailableException si está apagada, si el libro no se pudo leer
     *         o si otra instancia ya está conciliando
     */
    public LedgerReconciliation runNow(CurrentActor actor) {
        PaymentAccess.requireActiveAdmin(actor);
        if (!enabled) {
            throw new LedgerReconciliationUnavailableException(DISABLED,
                    "Ledger reconciliation is disabled until its reader is provisioned", null);
        }
        return await(submit()).orElseThrow(() -> new LedgerReconciliationUnavailableException(
                "LEDGER_RECONCILIATION_BUSY", "Another instance is reconciling the ledger; retry shortly", null));
    }

    private CompletableFuture<Optional<LedgerReconciliation>> submit() {
        synchronized (turn) {
            if (running == null || running.isDone()) {
                running = CompletableFuture.supplyAsync(() -> snapshot.execute(status -> reconcile()), worker);
            }
            return running;
        }
    }

    private Optional<LedgerReconciliation> await(CompletableFuture<Optional<LedgerReconciliation>> run) {
        try {
            // La transacción ya corta sola al agotar su tiempo; el margen es para recibir ese error.
            var outcome = run.get(timeout.toMillis() + graceMillis, TimeUnit.MILLISECONDS);
            unreadable.set(0);
            count(outcome.map(report -> report.consistent() ? "consistent" : "inconsistent").orElse("skipped"));
            return outcome;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unreadable(interrupted);
        } catch (ExecutionException failed) {
            throw unreadable(failed.getCause());
        } catch (TimeoutException slow) {
            throw unreadable(slow);
        }
    }

    private LedgerReconciliationUnavailableException unreadable(Throwable cause) {
        unreadable.set(1);
        count("unreadable");
        log.error("La conciliación de pagos no pudo leer el libro; no hay resultado", cause);
        return new LedgerReconciliationUnavailableException("LEDGER_UNREADABLE",
                "The payment ledger could not be read; no reconciliation result is available", cause);
    }

    private void count(String outcome) {
        if (meters != null) {
            meters.counter(RUNS, "outcome", outcome).increment();
        }
    }

    private Optional<LedgerReconciliation> reconcile() {
        if (!checks.openSystemRead(timeout)) {
            return Optional.empty();
        }
        var findings = new ArrayList<LedgerReconciliation.Finding>();
        for (var invariant : LedgerInvariant.values()) {
            var found = checks.check(invariant, SAMPLE_LIMIT);
            if (found.count() > 0) {
                findings.add(new LedgerReconciliation.Finding(invariant.name(), invariant.description(),
                        found.count(), found.sample()));
            }
        }
        var report = LedgerReconciliation.of(clock.instant().truncatedTo(ChronoUnit.MILLIS), checks.totals(),
                checks.scanned(), findings);
        latest.set(report);
        logOutcome(report);
        return Optional.of(report);
    }

    /**
     * El log lleva solo códigos y conteos: ni importes ni identificadores de fila. Esos quedan en la
     * respuesta, que solo ve un PLATFORM_ADMIN.
     */
    private static void logOutcome(LedgerReconciliation report) {
        if (report.consistent()) {
            log.info("Conciliación de pagos sin diferencias");
            return;
        }
        log.warn("Conciliación de pagos con diferencias: ecuaciones={} reglas={}",
                report.balances().stream().filter(balance -> !balance.holds())
                        .map(LedgerReconciliation.Balance::code)
                        .collect(Collectors.joining(", ")),
                report.findings().stream().map(f -> f.code() + "=" + f.count())
                        .collect(Collectors.joining(", ")));
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
