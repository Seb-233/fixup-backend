package com.fixup.payments.domain;

import java.util.List;
import java.util.UUID;

/** Consultas de solo lectura sobre todo el libro de pagos, sin filtrar por actor. */
public interface LedgerChecks {

    /** Filas que rompen la regla: el total y hasta {@code sampleLimit} identificadores. */
    Finding check(LedgerInvariant invariant, int sampleLimit);

    Totals totals();

    record Finding(long count, List<UUID> sample) {
        public Finding {
            sample = List.copyOf(sample);
        }
    }

    /**
     * Sumas globales en pesos enteros. {@code collected} es lo aprobado en pagos digitales;
     * {@code earningsGross} es lo que se repartió entre comisión y neto.
     */
    record Totals(long collected, long earningsGross, long commission, long net, long heldNet,
            long availableNet, long paidOutNet, long payouts, long outstandingDebts) {
    }
}
