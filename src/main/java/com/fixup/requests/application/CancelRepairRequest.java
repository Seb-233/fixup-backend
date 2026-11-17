package com.fixup.requests.application;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.api.StatusAuditTrail;
import com.fixup.audit.api.StatusChangeCommand;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quien reportó la falla o decide sobre el inmueble la retira mientras ningún técnico la tomó. */
@Service
public class CancelRepairRequest {
    private final RepairRequests requests;
    private final StatusAuditTrail auditTrail;

    CancelRepairRequest(RepairRequests requests, StatusAuditTrail auditTrail) {
        this.requests = requests;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId) {
        RequestAccess.requireActiveRequester(actor);
        var request = requests.findByIdForUpdate(requestId).orElseThrow(RepairRequestNotFoundException::new);
        if (!request.involves(actor.internalUserId())) {
            throw new RepairRequestAccessDeniedException();
        }
        var now = Instant.now();
        var cancelled = request.cancel(now);
        requests.update(cancelled);
        auditTrail.record(StatusChangeCommand.user(AuditedResourceType.REPAIR_REQUEST, cancelled.id(),
                request.status().name(), cancelled.status().name(), actor.internalUserId(),
                actor.externalSubject(), now));
        return RepairRequestSummary.of(cancelled);
    }
}
