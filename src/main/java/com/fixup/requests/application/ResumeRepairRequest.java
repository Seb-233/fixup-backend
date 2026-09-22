package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestResumed;
import com.fixup.requests.domain.RepairRequests;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-08: se levanta la pausa y el trabajo vuelve a estar en curso. */
@Service
public class ResumeRepairRequest {
    private final RepairRequests requests;
    private final ApplicationEventPublisher events;

    ResumeRepairRequest(RepairRequests requests, ApplicationEventPublisher events) {
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId) {
        RequestAccess.requireActive(actor);
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        var userId = actor.internalUserId();
        if (!request.ownerUserId().equals(userId) && !userId.equals(request.assignedFixerUserId())) {
            throw new RepairRequestAccessDeniedException();
        }
        var now = Instant.now();
        var resumed = request.resumeFromHold(now);
        requests.update(resumed);
        events.publishEvent(new RepairRequestResumed(resumed.id(), resumed.ownerUserId(),
                resumed.assignedFixerUserId(), now));
        return RepairRequestSummary.of(resumed);
    }
}
