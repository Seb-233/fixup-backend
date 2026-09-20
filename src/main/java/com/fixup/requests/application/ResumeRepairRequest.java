package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestResumed;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        RequestAccess.requireActiveFixer(actor);
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        if (!actor.hasRole(Role.PLATFORM_ADMIN)
                && (request.assignedFixerUserId() == null
                        || !request.assignedFixerUserId().equals(actor.internalUserId()))) {
            throw new RepairRequestAccessDeniedException();
        }
        var now = Instant.now();
        var updated = request.resumeFromHold(now);
        requests.update(updated);
        events.publishEvent(new RepairRequestResumed(updated.id(), updated.ownerUserId(),
                updated.assignedFixerUserId(), now));
        return RepairRequestSummary.of(updated);
    }
}
