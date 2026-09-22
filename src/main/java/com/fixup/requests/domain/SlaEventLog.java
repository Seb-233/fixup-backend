package com.fixup.requests.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * FR-UC-08: memoria de los avisos de SLA ya emitidos. El barrido corre cada pocos minutos sobre
 * las mismas solicitudes; sin esta bitácora, una solicitud vencida avisaría en cada pasada.
 */
public interface SlaEventLog {

    /** Identificadores, de entre los dados, que ya recibieron ese tipo de aviso. */
    Set<UUID> findRequestIdsAlreadyNotified(Collection<UUID> requestIds, SlaEventType eventType);

    void record(UUID requestId, SlaEventType eventType, Instant at);

    enum SlaEventType {
        SLA_WARNING, SLA_BREACH
    }
}
