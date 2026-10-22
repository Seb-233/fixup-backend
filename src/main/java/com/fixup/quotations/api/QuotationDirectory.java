package com.fixup.quotations.api;

import java.util.Set;
import java.util.UUID;

/**
 * FR-UC-03: una visita de diagnóstico se agenda con un técnico que ya cotizó la solicitud, para que
 * revise antes de que el propietario decida. visits pregunta aquí quiénes cotizaron.
 */
public interface QuotationDirectory {
    Set<UUID> fixersWhoQuoted(UUID requestId);
}
