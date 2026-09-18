package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.domain.RepairRequests;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-18: el detalle que el Fixer evalúa —descripción y fotos— antes de cotizar. */
@Service
public class GetRepairRequest {
    private final RepairRequests requests;

    GetRepairRequest(RepairRequests requests) {
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public RepairRequestSummary execute(CurrentActor actor, UUID requestId) {
        var request = requests.findById(requestId).orElseThrow(RepairRequestNotFoundException::new);
        request.requireVisibleTo(actor);
        return RepairRequestSummary.of(request);
    }
}
