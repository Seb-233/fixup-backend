package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.requests.api.Specialty;
import com.fixup.requests.domain.RepairRequests;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: la bandeja del Fixer. Ver la oferta abierta no exige estar verificado todavía; el
 * sello solo se exige para cotizar, de modo que un técnico en revisión puede ir mirando el mercado.
 */
@Service
public class ListOpenRepairRequests {
    private final RepairRequests requests;

    ListOpenRepairRequests(RepairRequests requests) {
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<RepairRequestSummary> execute(CurrentActor actor, Specialty specialty) {
        RequestAccess.requireActiveFixer(actor);
        return requests.findOpen(specialty).stream()
                // A fixer never sees his own request in the offer list.
                .filter(request -> !request.ownerUserId().equals(actor.internalUserId()))
                .map(RepairRequestSummary::of).toList();
    }
}
