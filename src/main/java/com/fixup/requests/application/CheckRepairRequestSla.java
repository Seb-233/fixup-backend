package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.requests.api.SlaBreachDue;
import com.fixup.requests.api.SlaWarningDue;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.SlaEventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;

/**
 * FR-UC-08: el reloj de SLA. Revisa las solicitudes vivas con plazo comprometido y anuncia dos
 * hechos distintos: que el plazo está por vencerse y que ya se venció.
 *
 * <p>El aviso previo se emite cuando queda el 20 % de la ventana, con un piso de una hora: para
 * una urgencia URGENT de cuatro horas eso significa avisar con una hora de margen y no con
 * cuarenta y ocho minutos, que no le sirve a nadie.
 *
 * <p>Cada aviso se emite una sola vez por solicitud gracias a la bitácora, que vive en la base y
 * no en memoria: reiniciar el backend no vuelve a notificar lo ya notificado.
 */
@Service
public class CheckRepairRequestSla {
    private static final Logger LOG = LoggerFactory.getLogger(CheckRepairRequestSla.class);

    private static final double WARNING_RATIO = 0.20;
    private static final Duration WARNING_MIN_LEAD = Duration.ofHours(1);
    /** Solo se traen las solicitudes cuyo plazo cae dentro de la próxima jornada. */
    private static final Duration LOOKAHEAD = Duration.ofDays(1);

    private final RepairRequests requests;
    private final SlaEventLog eventLog;
    private final ApplicationEventPublisher events;

    CheckRepairRequestSla(RepairRequests requests, SlaEventLog eventLog,
            ApplicationEventPublisher events) {
        this.requests = requests;
        this.eventLog = eventLog;
        this.events = events;
    }

    @Transactional
    public SweepResult sweep() {
        var now = Instant.now();
        var candidates = requests.findActiveWithSlaDeadlineBefore(now.plus(LOOKAHEAD));
        if (candidates.isEmpty()) {
            return new SweepResult(0, 0);
        }

        var ids = new HashSet<java.util.UUID>(candidates.size());
        candidates.forEach(request -> ids.add(request.id()));
        var alreadyWarned = eventLog.findRequestIdsAlreadyNotified(ids, SlaEventLog.SlaEventType.SLA_WARNING);
        var alreadyBreached = eventLog.findRequestIdsAlreadyNotified(ids, SlaEventLog.SlaEventType.SLA_BREACH);

        int warnings = 0;
        int breaches = 0;

        for (var request : candidates) {
            var remaining = Duration.between(now, request.slaDeadline());
            if (!remaining.isPositive()) {
                if (alreadyBreached.contains(request.id())) {
                    continue;
                }
                publishBreach(request);
                eventLog.record(request.id(), SlaEventLog.SlaEventType.SLA_BREACH, now);
                breaches++;
            } else if (remaining.compareTo(warningLead(request.urgency())) <= 0
                    && !alreadyWarned.contains(request.id())) {
                publishWarning(request, remaining);
                eventLog.record(request.id(), SlaEventLog.SlaEventType.SLA_WARNING, now);
                warnings++;
            }
        }

        if (warnings > 0 || breaches > 0) {
            LOG.info("SLA sweep: {} warning(s), {} breach(es)", warnings, breaches);
        }
        return new SweepResult(warnings, breaches);
    }

    private static Duration warningLead(RepairRequestUrgency urgency) {
        var window = urgency.slaWindow();
        var proportional = Duration.ofMillis((long) (window.toMillis() * WARNING_RATIO));
        return proportional.compareTo(WARNING_MIN_LEAD) < 0 ? WARNING_MIN_LEAD : proportional;
    }

    private void publishWarning(RepairRequest request, Duration remaining) {
        var message = "Faltan aproximadamente " + humanize(remaining)
                + " para el vencimiento del SLA de tu solicitud de " + humanize(request.specialty()) + ".";
        events.publishEvent(new SlaWarningDue(request.id(), request.ownerUserId(),
                request.assignedFixerUserId(), "Tu reparación se acerca al plazo SLA", message));
    }

    private void publishBreach(RepairRequest request) {
        var message = "La reparación venció el plazo SLA estimado para su urgencia "
                + request.urgency().name().toLowerCase(Locale.ROOT) + ".";
        events.publishEvent(new SlaBreachDue(request.id(), request.ownerUserId(),
                request.assignedFixerUserId(), "Plazo SLA vencido", message));
    }

    private static String humanize(Specialty specialty) {
        if (specialty == null) {
            return "reparación";
        }
        var name = specialty.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String humanize(Duration remaining) {
        long hours = remaining.toHours();
        if (hours >= 24) {
            long days = remaining.toDays();
            return days == 1 ? "1 día" : days + " días";
        }
        if (hours > 0) {
            long minutes = remaining.toMinutesPart();
            return hours + (hours == 1 ? " hora" : " horas")
                    + (minutes > 0 ? " y " + minutes + " min" : "");
        }
        long minutes = remaining.toMinutes();
        return minutes + (minutes == 1 ? " minuto" : " minutos");
    }

    /** Lo que hizo el barrido, para que una prueba pueda afirmar sobre ello sin leer los logs. */
    public record SweepResult(int warnings, int breaches) {}
}
