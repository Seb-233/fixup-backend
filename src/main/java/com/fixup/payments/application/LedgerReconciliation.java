package com.fixup.payments.application;

import com.fixup.payments.domain.LedgerChecks;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de una conciliación. {@code consistent} solo es verdadero si ninguna regla tiene
 * filas y si lo cobrado coincide con lo repartido entre comisión y neto.
 */
public record LedgerReconciliation(Instant checkedAt, boolean consistent, boolean balanced,
        Totals totals, List<Finding> findings) {

    public LedgerReconciliation {
        findings = List.copyOf(findings);
    }

    public long violations() {
        return findings.stream().mapToLong(Finding::count).sum();
    }

    @Schema(name = "LedgerFinding")
    public record Finding(String code, String description, long count, List<UUID> sample) {
        public Finding {
            sample = List.copyOf(sample);
        }
    }

    @Schema(name = "LedgerTotals")
    public record Totals(long collected, long earningsGross, long commission, long net, long heldNet,
            long availableNet, long paidOutNet, long payouts, long outstandingDebts) {
        static Totals of(LedgerChecks.Totals t) {
            return new Totals(t.collected(), t.earningsGross(), t.commission(), t.net(), t.heldNet(),
                    t.availableNet(), t.paidOutNet(), t.payouts(), t.outstandingDebts());
        }
    }
}
