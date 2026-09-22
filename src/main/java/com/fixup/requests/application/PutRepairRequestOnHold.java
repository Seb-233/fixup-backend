package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestPutOnHold;
import com.fixup.requests.domain.RepairRequests;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-08: el trabajo se detiene sin cancelarse. Pueden hacerlo tanto el dueño como el técnico
 * asignado, porque la causa puede estar de cualquiera de los dos lados.
 */
@Service
public class PutRepairRequestOnHold {
    private final RepairRequests requests;
    private final ApplicationEventPublisher events;

    PutRepairRequestOnHold(RepairRequests requests, ApplicationEventPublisher events) {
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
        var held = request.putOnHold(now);
        requests.update(held);
        events.publishEvent(new RepairRequestPutOnHold(held.id(), held.ownerUserId(),
                held.assignedFixerUserId(), now));
        return RepairRequestSummary.of(held);
    }
}
