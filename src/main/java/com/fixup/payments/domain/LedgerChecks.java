package com.fixup.payments.domain;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Consultas de solo lectura sobre todo el libro de pagos, sin filtrar por actor. */
public interface LedgerChecks {

    /**
     * Prepara la transacción actual para leer el libro completo. Tiene que ser lo primero que se
     * llame dentro de la transacción de la conciliación.
     *
     * <p>En PostgreSQL toma el turno entre réplicas, pasa al rol lector de mínimo privilegio
     * (fixup_ledger_auditor) y limita cuánto puede tardar cada consulta. Si no puede asumir ese
     * rol lanza una excepción: una conciliación que no ve el libro no puede decir que cuadra.
     *
     * @return {@code false} si otra instancia ya está conciliando y esta no debe repetir el trabajo
     */
    boolean openSystemRead(Duration statementTimeout);

    /** Filas que rompen la regla: el total y hasta {@code sampleLimit} identificadores. */
    Finding check(LedgerInvariant invariant, int sampleLimit);

    Totals totals();

    /** Cuántas filas de cada tabla entraron en la lectura. */
    Scanned scanned();

    record Finding(long count, List<UUID> sample) {
        public Finding {
            sample = List.copyOf(sample);
        }
    }

    /**
     * Sumas globales en pesos enteros (COP no tiene centavos en el libro), separadas por tipo de
     * operación para que ninguna ecuación cuente dos veces la misma plata. Ver {@link LedgerEquation}.
     *
     * @param serviceCollected pagos digitales aprobados de servicio
     * @param debtCollected pagos digitales aprobados de saldo pendiente
     * @param serviceEarningsGross bruto de los ingresos respaldados por un pago de servicio aprobado
     * @param debtEarningsGross bruto de los ingresos respaldados por un pago de saldo aprobado
     * @param legacyEarningsGross bruto de los ingresos anteriores al corte digital (V41) sin pago que cruzar
     * @param composedPayouts transferencias que guardan su composición (desde V41)
     * @param legacyPayouts transferencias anteriores a V41, sin composición
     * @param payoutEntriesNet suma de los renglones de las transferencias
     * @param composedPaidOutNet neto de los ingresos transferidos que están en un renglón
     * @param legacyPaidOutNet neto de los ingresos transferidos sin renglón (anteriores a V41)
     * @param cashAgreed valor acordado de los trabajos liquidados en efectivo (históricos)
     * @param cashReceived efectivo que el técnico recibió en mano; nunca pasó por la plataforma
     * @param cashOutstanding lo que quedó debiendo el propietario al cerrar esos trabajos
     */
    record Totals(long serviceCollected, long debtCollected, long earningsGross, long serviceEarningsGross,
            long debtEarningsGross, long legacyEarningsGross, long commission, long net, long heldNet,
            long availableNet, long paidOutNet, long composedPayouts, long legacyPayouts,
            long payoutEntriesNet, long composedPaidOutNet, long legacyPaidOutNet, long paidDebts,
            long outstandingDebts, long cashAgreed, long cashReceived, long cashOutstanding) {

        public long collected() {
            return serviceCollected + debtCollected;
        }

        public long payouts() {
            return composedPayouts + legacyPayouts;
        }
    }

    record Scanned(long payments, long paymentAttempts, long earnings, long payouts, long payoutEntries,
            long debts, long cashSettlements) {
    }
}
