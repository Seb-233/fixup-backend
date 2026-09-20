package com.fixup.requests.infrastructure;

import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.SlaBreachDue;
import com.fixup.requests.api.SlaWarningDue;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SlaCheckScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SlaCheckScheduler.class);

    private static final double WARNING_RATIO = 0.20;
    private static final Duration WARNING_MIN_LEAD = Duration.ofHours(1);

    private final RepairRequests requests;
    private final ApplicationEventPublisher events;
    private final SlaEventLogJpaRepository eventLog;

    SlaCheckScheduler(RepairRequests requests, ApplicationEventPublisher events,
            SlaEventLogJpaRepository eventLog) {
        this.requests = requests;
        this.events = events;
        this.eventLog = eventLog;
    }

    @Scheduled(initialDelayString = "${fixup.sla.initial-delay-ms:30000}",
            fixedDelayString = "${fixup.sla.fixed-delay-ms:300000}")
    @Transactional
    public void checkActiveRequests() {
        var now = Instant.now();
        var candidates = requests.findActiveWithSlaDeadlineBefore(now.plus(Duration.ofDays(1)));
        if (candidates.isEmpty()) {
            return;
        }
        var ids = new HashSet<UUID>(candidates.size());
        Map<UUID, RepairRequest> byId = new HashMap<>(candidates.size());
        for (var r : candidates) {
            ids.add(r.id());
            byId.put(r.id(), r);
        }
        var warned = eventLog.findRequestIdsWithEvent(ids, "SLA_WARNING");
        var breached = eventLog.findRequestIdsWithEvent(ids, "SLA_BREACH");

        var warningsToLog = new ArrayList<SlaEventLogEntity>();
        var breachesToLog = new ArrayList<SlaEventLogEntity>();

        for (var r : candidates) {
            if (r.slaDeadline() == null) continue;
            var remaining = Duration.between(now, r.slaDeadline());
            var total = r.urgency().slaWindow();
            var warningLead = Duration.ofMillis(Math.max(WARNING_MIN_LEAD.toMillis(),
                    (long) (total.toMillis() * WARNING_RATIO)));

            if (remaining.isNegative() || remaining.isZero()) {
                if (!breached.contains(r.id())) {
                    publishBreach(r);
                    breachesToLog.add(SlaEventLogEntity.breachFor(r.id(), now));
                }
            } else if (remaining.compareTo(warningLead) <= 0 && !warned.contains(r.id())) {
                publishWarning(r, remaining);
                warningsToLog.add(SlaEventLogEntity.warningFor(r.id(), now));
            }
        }

        if (!warningsToLog.isEmpty()) {
            eventLog.saveAllAndFlush(warningsToLog);
        }
        if (!breachesToLog.isEmpty()) {
            eventLog.saveAllAndFlush(breachesToLog);
        }
        if (!warningsToLog.isEmpty() || !breachesToLog.isEmpty()) {
            LOG.info("SLA sweep: {} warning(s), {} breach(es)", warningsToLog.size(), breachesToLog.size());
        }
    }

    private void publishWarning(RepairRequest r, Duration remaining) {
        String title = "Tu reparación se acerca al plazo SLA";
        String message = new StringBuilder()
                .append("Faltan aproximadamente ").append(humanize(remaining))
                .append(" para el vencimiento del SLA para tu solicitud de ")
                .append(humanize(r.specialty())).append('.')
                .toString();
        events.publishEvent(new SlaWarningDue(r.id(), r.ownerUserId(), r.assignedFixerUserId(),
                title, message));
    }

    private void publishBreach(RepairRequest r) {
        String title = "Plazo SLA vencido";
        String message = "La reparación venció el plazo SLA estimado para su urgencia "
                + humanize(r.urgency()) + '.';
        events.publishEvent(new SlaBreachDue(r.id(), r.ownerUserId(), r.assignedFixerUserId(),
                title, message));
    }

    private static String humanize(Specialty specialty) {
        if (specialty == null) return "reparación";
        var s = specialty.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return s.isEmpty() ? "reparación" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String humanize(RepairRequestUrgency urgency) {
        return urgency == null ? "normal" : urgency.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String humanize(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return "poco tiempo";
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours >= 24) {
            long days = d.toDays();
            return days == 1 ? "1 día" : days + " días";
        }
        if (hours > 0) {
            return hours + (hours == 1 ? " hora" : " horas")
                    + (minutes > 0 ? " y " + minutes + " min" : "");
        }
        return minutes + (minutes == 1 ? " minuto" : " minutos");
    }
}
