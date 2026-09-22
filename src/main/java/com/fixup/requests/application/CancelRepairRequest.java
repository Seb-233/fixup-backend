package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestCancelled;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.domain.RepairRequests;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-08: solo el dueño desiste de su solicitud; la regla vive en el propio registro. */
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
        var cancelled = request.cancel(now, actor.internalUserId());
        requests.update(cancelled);
        events.publishEvent(new RepairRequestCancelled(cancelled.id(), cancelled.ownerUserId(),
                cancelled.assignedFixerUserId(), now));
        return RepairRequestSummary.of(cancelled);
    }
}
