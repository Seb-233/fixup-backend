package com.fixup.requests.domain;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaState;
import java.time.Duration;
import java.time.Instant;

/**
 * RNF-07: la politica del SLA de reparaciones urgentes se declara una sola vez. El compromiso son
 * 48 horas desde la apertura de la solicitud y el aviso previo se dispara cuando resta el 20 % del
 * plazo. El tablero, el resumen y el verificador periodico derivan de aqui para que no puedan
 * discrepar entre si.
 */
public final class SlaPolicy {

    /** Plazo comprometido para una solicitud urgente. */
    public static final Duration URGENT_DURATION = Duration.ofHours(48);

    /** Fraccion del plazo que queda cuando se avisa que el SLA esta por vencer. */
    public static final double WARNING_RATIO = 0.20;

    /** Tiempo restante a partir del cual la solicitud entra en aviso previo. */
    public static final Duration WARNING_THRESHOLD =
            Duration.ofMinutes(Math.round(URGENT_DURATION.toMinutes() * WARNING_RATIO));

    private SlaPolicy() {
    }

    /** Instante limite del compromiso para una solicitud urgente abierta en {@code openedAt}. */
    public static Instant urgentDeadline(Instant openedAt) {
        return openedAt.plus(URGENT_DURATION);
    }

    /**
     * Estado del SLA tal como lo ven el tablero y el resumen: el estado persistido manda mientras la
     * solicitud siga abierta, y si no se deriva del tiempo restante hasta el limite.
     */
    public static SlaState stateOf(RepairRequestStatus status, Instant deadline, Instant now) {
        if (deadline == null) {
            return SlaState.NONE;
        }
        if (status == RepairRequestStatus.SLA_BREACHED) {
            return SlaState.BREACHED;
        }
        if (status == RepairRequestStatus.SLA_WARNING) {
            return SlaState.WARNING;
        }
        if (deadline.isBefore(now)) {
            return SlaState.BREACHED;
        }
        return Duration.between(now, deadline).compareTo(WARNING_THRESHOLD) <= 0
                ? SlaState.WARNING
                : SlaState.ON_TRACK;
    }
}
