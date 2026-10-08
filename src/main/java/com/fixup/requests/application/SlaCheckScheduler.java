package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaBreached;
import com.fixup.requests.api.SlaWarningRaised;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SlaCheckScheduler {

    private static final Duration WARNING_THRESHOLD = Duration.ofMinutes((long) (48L * 60L * 0.20));
    private static final Set<RepairRequestStatus> TERMINAL_SLA_STATUSES = Set.of(
            RepairRequestStatus.SLA_WARNING, RepairRequestStatus.SLA_BREACHED,
            RepairRequestStatus.COMPLETED, RepairRequestStatus.CANCELLED);

    private final RepairRequests requests;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SlaCheckScheduler(RepairRequests requests, ApplicationEventPublisher eventPublisher,
            java.util.Optional<Clock> clock) {
        this.requests = requests;
        this.eventPublisher = eventPublisher;
        this.clock = clock.orElse(Clock.systemUTC());
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
            if (TERMINAL_SLA_STATUSES.contains(request.status())) {
                continue;
            }

            Duration remaining = Duration.between(now, request.slaDeadline());
            boolean isBreached = remaining.isNegative() || remaining.isZero();
            boolean isWarning = !isBreached && remaining.compareTo(WARNING_THRESHOLD) <= 0;

            long remainingMinutes = Math.max(0, remaining.toMinutes());

            if (isBreached && request.status() != RepairRequestStatus.SLA_BREACHED) {
                RepairRequest updated = request
                        .withStatus(RepairRequestStatus.SLA_BREACHED, now)
                        .withLastEscalationNotifiedAt(now);
                requests.update(updated);
                eventPublisher.publishEvent(new SlaBreached(
                        updated.id(), updated.propertyId(), updated.ownerUserId(), remainingMinutes));
            } else if (isWarning && request.status() != RepairRequestStatus.SLA_WARNING
                    && request.status() != RepairRequestStatus.SLA_BREACHED) {
                RepairRequest updated = request.withStatus(RepairRequestStatus.SLA_WARNING, now);
                requests.update(updated);
                eventPublisher.publishEvent(new SlaWarningRaised(
                        updated.id(), updated.propertyId(), updated.ownerUserId(), remainingMinutes));
            }
        }
    }
}
