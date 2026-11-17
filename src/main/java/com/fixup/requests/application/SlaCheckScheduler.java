package com.fixup.requests.application;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.api.StatusAuditTrail;
import com.fixup.audit.api.StatusChangeCommand;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaBreached;
import com.fixup.requests.api.SlaWarningRaised;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.SlaPolicy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class SlaCheckScheduler {

    private static final Set<RepairRequestStatus> FINAL_SLA_STATUSES = Set.of(
            RepairRequestStatus.SLA_BREACHED, RepairRequestStatus.COMPLETED,
            RepairRequestStatus.CANCELLED);

    private final RepairRequests requests;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final StatusAuditTrail auditTrail;
    private final UserDirectory userDirectory;

    public SlaCheckScheduler(RepairRequests requests, ApplicationEventPublisher eventPublisher,
            java.util.Optional<Clock> clock, StatusAuditTrail auditTrail, UserDirectory userDirectory) {
        this.requests = requests;
        this.eventPublisher = eventPublisher;
        this.clock = clock.orElse(Clock.systemUTC());
        this.auditTrail = auditTrail;
        this.userDirectory = userDirectory;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void checkSlas() {
        Instant now = Instant.now(clock);
        var urgent = requests.findUrgentUnresolved();
        for (RepairRequest request : urgent) {
            if (request.slaDeadline() == null) {
                continue;
            }
            if (FINAL_SLA_STATUSES.contains(request.status())) {
                continue;
            }

            Duration remaining = Duration.between(now, request.slaDeadline());
            boolean isBreached = remaining.isNegative() || remaining.isZero();
            boolean isWarning = !isBreached && remaining.compareTo(SlaPolicy.WARNING_THRESHOLD) <= 0;

            long remainingMinutes = Math.max(0, remaining.toMinutes());

            if (isBreached && request.status() != RepairRequestStatus.SLA_BREACHED) {
                RepairRequest updated = request
                        .withStatus(RepairRequestStatus.SLA_BREACHED, now)
                        .withLastEscalationNotifiedAt(now);
                requests.update(updated);
                auditTrail.record(StatusChangeCommand.system(AuditedResourceType.REPAIR_REQUEST,
                        updated.id(), request.status().name(), updated.status().name(), now));
                eventPublisher.publishEvent(new SlaBreached(
                        updated.id(), updated.propertyId(), updated.ownerUserId(), remainingMinutes));
                notifyEscalation(updated, NotificationType.REQUEST_SLA_BREACHED, "SLA incumplido",
                        "\"" + updated.title() + "\" superó las 48 horas sin quedar resuelta. Se escaló al administrador.");
            } else if (isWarning && request.status() != RepairRequestStatus.SLA_WARNING
                    && request.status() != RepairRequestStatus.SLA_BREACHED) {
                RepairRequest updated = request.withStatus(RepairRequestStatus.SLA_WARNING, now);
                requests.update(updated);
                auditTrail.record(StatusChangeCommand.system(AuditedResourceType.REPAIR_REQUEST,
                        updated.id(), request.status().name(), updated.status().name(), now));
                eventPublisher.publishEvent(new SlaWarningRaised(
                        updated.id(), updated.propertyId(), updated.ownerUserId(), remainingMinutes));
                notifyEscalation(updated, NotificationType.REQUEST_SLA_WARNING, "SLA por vencer",
                        "A \"" + updated.title() + "\" le quedan " + remainingMinutes + " minutos de SLA.");
            }
        }
    }

    /**
     * RNF-07: el aviso previo y el escalamiento llegan a quien decide, a quien ejecuta y, al
     * administrador de la plataforma, que es quien garantiza el compromiso de las 48 horas. Los
     * destinatarios se deduplican por identificador porque una misma persona puede coincidir en
     * varios papeles.
     */
    private void notifyEscalation(RepairRequest request, NotificationType type, String title, String body) {
        var recipients = new LinkedHashSet<UUID>();
        recipients.add(request.ownerUserId());
        if (request.managerUserId() != null) {
            recipients.add(request.managerUserId());
        }
        if (request.assignedFixerUserId() != null) {
            recipients.add(request.assignedFixerUserId());
        }
        userDirectory.activeUsersWithRole(Role.PLATFORM_ADMIN).stream()
                .map(UserDirectory.UserCard::id)
                .forEach(recipients::add);
        for (UUID recipient : recipients) {
            eventPublisher.publishEvent(NotificationRequested.of(recipient, type, title, body,
                    "/requests/" + request.id(), request.id(), "REQUEST"));
        }
    }
}
