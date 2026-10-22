package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.domain.RepairRequests;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-04, FR-UC-08 y FR-UC-11: las solicitudes que el usuario abrió o que pertenecen a un inmueble
 * que posee o administra, más recientes primero.
 */
@Service
public class ListOwnRepairRequests {
    private final RepairRequests requests;

    ListOwnRepairRequests(RepairRequests requests) {
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<RepairRequestSummary> execute(CurrentActor actor) {
        RequestAccess.requireActiveRequester(actor);
        return requests.findByParticipant(actor.internalUserId()).stream().map(RepairRequestSummary::of).toList();
    }
}
