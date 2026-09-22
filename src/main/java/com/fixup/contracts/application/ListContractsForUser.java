package com.fixup.contracts.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.domain.Contracts;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListContractsForUser {
    private final Contracts contracts;

    ListContractsForUser(Contracts contracts) {
        this.contracts = contracts;
    }

    @Transactional(readOnly = true)
    public List<ContractSummary> execute(CurrentActor actor, String role, ContractStatus status,
            UUID propertyId, LocalDate startFrom, LocalDate endUntil, int limit) {
        ContractAccess.requireActiveUser(actor);
        ContractAccess.requireTenantOrOwner(actor);
        var safeRole = (role == null || role.isBlank()) ? "ANY" : role;
        return ContractSummary.of(contracts.findByUser(actor.internalUserId(), safeRole, status,
                propertyId, startFrom, endUntil, limit));
    }
}
