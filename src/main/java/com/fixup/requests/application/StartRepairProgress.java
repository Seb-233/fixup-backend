package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestProgressStarted;
import com.fixup.requests.domain.RepairRequests;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-08: el técnico asignado declara que empezó el trabajo. */
@Service
public class StartRepairProgress {
    private final RepairRequests requests;
    private final ApplicationEventPublisher events;

    StartRepairProgress(RepairRequests requests, ApplicationEventPublisher events) {
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId) {
        RequestAccess.requireActiveFixer(actor);
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        if (!actor.internalUserId().equals(request.assignedFixerUserId())) {
            throw new RepairRequestAccessDeniedException();
        }
        var now = Instant.now();
        var started = request.startProgress(now);
        requests.update(started);
        events.publishEvent(new RepairRequestProgressStarted(started.id(), started.ownerUserId(),
                started.assignedFixerUserId(), now));
        return RepairRequestSummary.of(started);
    }
}
