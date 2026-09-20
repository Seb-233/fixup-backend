package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestCancelled;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelRepairRequest {
    private final RepairRequests requests;
    private final ApplicationEventPublisher events;

    CancelRepairRequest(RepairRequests requests, ApplicationEventPublisher events) {
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId) {
        RequestAccess.requireActiveRequester(actor);
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        var now = Instant.now();
        var updated = request.cancel(now, actor.internalUserId());
        requests.update(updated);
        events.publishEvent(new RepairRequestCancelled(updated.id(), updated.ownerUserId(),
                updated.assignedFixerUserId(), now));
        return RepairRequestSummary.of(updated);
    }
}
