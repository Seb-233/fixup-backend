package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.domain.RepairRequests;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListOwnRepairRequests {
    private final RepairRequests requests;

    ListOwnRepairRequests(RepairRequests requests) {
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<RepairRequestSummary> execute(CurrentActor actor) {
        RequestAccess.requireActiveRequester(actor);
        return requests.findByOwner(actor.internalUserId()).stream().map(RepairRequestSummary::of).toList();
    }
}
