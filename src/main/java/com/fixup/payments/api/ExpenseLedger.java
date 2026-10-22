package com.fixup.payments.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-13: el gasto real de un inmueble es lo que se pagó por reparaciones (FR-UC-05): las
 * transferencias aprobadas, incluido el pago de saldos pendientes, y el efectivo que el técnico
 * declaró recibido. analytics arma los reportes leyendo esta lista; payments decide qué pagos puede
 * ver cada actor.
 */
public interface ExpenseLedger {

    /**
     * Pagos entre las fechas que el actor debe o pagó: como propietario, o como administrador
     * inmobiliario de los inmuebles delegados. El efectivo es un acuerdo directo entre propietario y
     * técnico, así que solo aparece en el reporte del propietario.
     */
    List<Expense> approvedExpenses(CurrentActor actor, Instant from, Instant to);

    enum Kind { TRANSFER, OUTSTANDING_BALANCE, CASH }

    record Expense(UUID paymentId, UUID requestId, Kind kind, long amount, UUID fixerUserId, Instant paidAt) {
    }
}
