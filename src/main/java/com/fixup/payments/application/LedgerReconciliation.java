package com.fixup.payments.application;

import com.fixup.payments.domain.LedgerChecks;
import com.fixup.payments.domain.LedgerEquation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de una conciliación que sí leyó el libro. {@code consistent} solo es verdadero si
 * ninguna regla tiene filas y todas las ecuaciones cuadran. Si el libro no se pudo leer no hay
 * resultado: el servicio responde 503, nunca un "cuadra" vacío.
 *
 * <p>Todos los importes son pesos colombianos enteros. No incluye datos personales: solo sumas,
 * conteos e identificadores de fila.
 */
public record LedgerReconciliation(Instant checkedAt, boolean consistent, boolean balanced,
        Totals totals, Scanned scanned, List<Balance> balances, List<Finding> findings) {

    public LedgerReconciliation {
        balances = List.copyOf(balances);
        findings = List.copyOf(findings);
    }

    static LedgerReconciliation of(Instant checkedAt, LedgerChecks.Totals totals, LedgerChecks.Scanned scanned,
            List<Finding> findings) {
        var balances = Arrays.stream(LedgerEquation.values()).map(equation -> Balance.of(equation, totals)).toList();
        boolean balanced = balances.stream().allMatch(Balance::holds);
        return new LedgerReconciliation(checkedAt, balanced && findings.isEmpty(), balanced, Totals.of(totals),
                Scanned.of(scanned), balances, findings);
    }

    /** Filas que rompen alguna regla más ecuaciones que no cuadran. */
    public long violations() {
        return findings.stream().mapToLong(Finding::count).sum()
                + balances.stream().filter(balance -> !balance.holds()).count();
    }

    @Schema(name = "LedgerFinding")
    public record Finding(String code, String description, long count, List<UUID> sample) {
        public Finding {
            sample = List.copyOf(sample);
        }
    }

    /** Una ecuación de conservación: {@code holds} es {@code expected == actual}. */
    @Schema(name = "LedgerBalance")
    public record Balance(String code, String description, long expected, long actual, boolean holds) {
        static Balance of(LedgerEquation equation, LedgerChecks.Totals totals) {
            long left = equation.left(totals);
            long right = equation.right(totals);
            return new Balance(equation.name(), equation.description(), left, right, left == right);
        }
    }

    @Schema(name = "LedgerTotals")
    public record Totals(long collected, long serviceCollected, long debtCollected, long earningsGross,
            long serviceEarningsGross, long debtEarningsGross, long legacyEarningsGross, long commission,
            long net, long heldNet, long availableNet, long paidOutNet, long payouts, long composedPayouts,
            long legacyPayouts, long payoutEntriesNet, long legacyPaidOutNet, long paidDebts,
            long outstandingDebts, long cashAgreed, long cashReceived, long cashOutstanding) {
        static Totals of(LedgerChecks.Totals t) {
            return new Totals(t.collected(), t.serviceCollected(), t.debtCollected(), t.earningsGross(),
                    t.serviceEarningsGross(), t.debtEarningsGross(), t.legacyEarningsGross(), t.commission(),
                    t.net(), t.heldNet(), t.availableNet(), t.paidOutNet(), t.payouts(), t.composedPayouts(),
                    t.legacyPayouts(), t.payoutEntriesNet(), t.legacyPaidOutNet(), t.paidDebts(),
                    t.outstandingDebts(), t.cashAgreed(), t.cashReceived(), t.cashOutstanding());
        }
    }

    /** Filas leídas por tabla: distingue "sin hallazgos sobre N filas" de un libro vacío. */
    @Schema(name = "LedgerScanned")
    public record Scanned(long payments, long paymentAttempts, long earnings, long payouts, long payoutEntries,
            long debts, long cashSettlements) {
        static Scanned of(LedgerChecks.Scanned s) {
            return new Scanned(s.payments(), s.paymentAttempts(), s.earnings(), s.payouts(), s.payoutEntries(),
                    s.debts(), s.cashSettlements());
        }
    }
}
