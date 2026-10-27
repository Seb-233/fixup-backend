package com.fixup.payments.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-13: el gasto real de un inmueble es lo que su propietario pagó por reparaciones. analytics
 * arma los reportes leyendo esta lista; payments decide qué cobros puede ver cada actor.
 */
public interface ExpenseLedger {

    /** Cobros aprobados entre las fechas, del propietario o de los inmuebles que el actor administra. */
    List<Expense> approvedExpenses(CurrentActor actor, Instant from, Instant to);

    record Expense(UUID chargeId, UUID propertyId, UUID requestId, String specialty, String concept, long amount,
            UUID fixerUserId, Instant approvedAt) {
    }
}
