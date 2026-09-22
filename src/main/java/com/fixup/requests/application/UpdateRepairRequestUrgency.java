package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.requests.api.RepairRequestUrgencyChanged;
import com.fixup.requests.domain.RepairRequests;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-08: el propietario reclasifica la urgencia de su solicitud y con eso mueve el plazo
 * comprometido. Solo se anuncia el cambio si de verdad cambió algo: reenviar la misma urgencia no
 * es un evento y no debe llenar la bandeja de notificaciones.
 */
@Service
public class UpdateRepairRequestUrgency {
    private final RepairRequests requests;
    private final ApplicationEventPublisher events;

    UpdateRepairRequestUrgency(RepairRequests requests, ApplicationEventPublisher events) {
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId, RepairRequestUrgency urgency) {
        RequestAccess.requireActiveRequester(actor);
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        if (!actor.hasRole(Role.PLATFORM_ADMIN)
                && !request.ownerUserId().equals(actor.internalUserId())) {
            throw new RepairRequestAccessDeniedException();
        }
        var previousUrgency = request.urgency();
        var now = Instant.now();
        var updated = request.withUrgency(urgency, now);
        requests.update(updated);
        if (previousUrgency != updated.urgency()) {
            events.publishEvent(new RepairRequestUrgencyChanged(updated.id(), updated.ownerUserId(),
                    updated.assignedFixerUserId(), previousUrgency, updated.urgency(), now));
        }
        return RepairRequestSummary.of(updated);
    }
}
