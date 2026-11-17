package com.fixup.payments.domain;

import java.util.function.ToLongFunction;

/**
 * Ecuaciones de conservación del libro, una por tipo de operación. Cada peso entra por un solo
 * lado de una sola ecuación de su tipo, así que un pago de saldo pendiente nunca se compara contra
 * ingresos de servicio ni al revés.
 *
 * <p>Qué queda por fuera y por qué:
 * <ul>
 *   <li>El efectivo recibido en mano ({@code cashReceived}) nunca pasó por la plataforma: no genera
 *       pago digital, ingreso ni comisión. Solo se comprueba contra lo acordado y la deuda.</li>
 *   <li>Los ingresos anteriores al corte digital (V41) que no tienen pago que cruzar se muestran en
 *       {@code legacyEarningsGross} y solo entran en el reparto comisión + neto.</li>
 *   <li>Las transferencias anteriores a V41 no guardan su composición: se muestran en
 *       {@code legacyPayouts} y {@code legacyPaidOutNet} y no entran en las ecuaciones de renglones.</li>
 *   <li>Los pagos pendientes, rechazados o con error no son plata cobrada y no entran en ninguna.</li>
 * </ul>
 */
public enum LedgerEquation {
    SERVICE_COLLECTED_EQUALS_SERVICE_EARNINGS(
            "Lo cobrado por servicios es igual al bruto de los ingresos de servicio",
            LedgerChecks.Totals::serviceCollected, LedgerChecks.Totals::serviceEarningsGross),
    DEBT_COLLECTED_EQUALS_DEBT_EARNINGS(
            "Lo cobrado por saldos pendientes es igual al bruto de los ingresos de saldo",
            LedgerChecks.Totals::debtCollected, LedgerChecks.Totals::debtEarningsGross),
    DEBT_COLLECTED_EQUALS_PAID_DEBTS(
            "Lo cobrado por saldos pendientes es igual a las deudas marcadas como pagadas",
            LedgerChecks.Totals::debtCollected, LedgerChecks.Totals::paidDebts),
    EVERY_EARNING_HAS_ONE_ORIGIN(
            "Cada ingreso viene de un solo origen: servicio, saldo pendiente o histórico",
            LedgerChecks.Totals::earningsGross,
            t -> t.serviceEarningsGross() + t.debtEarningsGross() + t.legacyEarningsGross()),
    EARNINGS_SPLIT_INTO_COMMISSION_AND_NET(
            "El bruto de los ingresos es igual a comisión más neto",
            LedgerChecks.Totals::earningsGross, t -> t.commission() + t.net()),
    NET_SPLIT_BY_STATUS(
            "El neto es igual a retenido más disponible más transferido",
            LedgerChecks.Totals::net, t -> t.heldNet() + t.availableNet() + t.paidOutNet()),
    PAYOUTS_EQUAL_THEIR_ENTRIES(
            "Las transferencias con composición suman lo mismo que sus renglones",
            LedgerChecks.Totals::composedPayouts, LedgerChecks.Totals::payoutEntriesNet),
    PAYOUT_ENTRIES_EQUAL_PAID_OUT_EARNINGS(
            "Los renglones de transferencia suman lo mismo que los ingresos transferidos que contienen",
            LedgerChecks.Totals::payoutEntriesNet, LedgerChecks.Totals::composedPaidOutNet),
    CASH_AGREED_SPLIT_INTO_RECEIVED_AND_OUTSTANDING(
            "Lo acordado en efectivo es igual a lo recibido más lo que quedó pendiente",
            LedgerChecks.Totals::cashAgreed, t -> t.cashReceived() + t.cashOutstanding()),
    CASH_OUTSTANDING_EQUALS_DEBTS(
            "Lo que quedó pendiente en efectivo es igual a las deudas, pagadas y por pagar",
            LedgerChecks.Totals::cashOutstanding, t -> t.paidDebts() + t.outstandingDebts());

    private final String description;
    private final ToLongFunction<LedgerChecks.Totals> left;
    private final ToLongFunction<LedgerChecks.Totals> right;

    LedgerEquation(String description, ToLongFunction<LedgerChecks.Totals> left,
            ToLongFunction<LedgerChecks.Totals> right) {
        this.description = description;
        this.left = left;
        this.right = right;
    }

    public String description() {
        return description;
    }

    public long left(LedgerChecks.Totals totals) {
        return left.applyAsLong(totals);
    }

    public long right(LedgerChecks.Totals totals) {
        return right.applyAsLong(totals);
    }
}
