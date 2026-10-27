package com.fixup.requests.application;

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

    CancelRepairRequest(RepairRequests requests) {
        this.requests = requests;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId) {
        RequestAccess.requireActiveRequester(actor);
        var request = requests.findByIdForUpdate(requestId).orElseThrow(RepairRequestNotFoundException::new);
        if (!request.involves(actor.internalUserId())) {
            throw new RepairRequestAccessDeniedException();
        }
        var cancelled = request.cancel(Instant.now());
        requests.update(cancelled);
        return RepairRequestSummary.of(cancelled);
    }
}
