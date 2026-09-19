package com.fixup.contracts.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.domain.Contracts;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminateLeaseContract {
    private final Contracts contracts;

    TerminateLeaseContract(Contracts contracts) {
        this.contracts = contracts;
    }

    @Transactional
    public ContractSummary asOwner(CurrentActor actor, UUID contractId,
            @Size(max = 2000) String reason) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findByIdForUpdate(contractId).orElseThrow(ContractNotFoundException::new);
        if (!c.ownerUserId().equals(actor.internalUserId())
                && !(c.realEstateManagerUserId() != null
                        && c.realEstateManagerUserId().equals(actor.internalUserId()))
                && !actor.hasRole(com.fixup.identityaccess.api.Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
        var now = Instant.now();
        var terminated = c.terminateAsOwner(reason, now);
        contracts.update(terminated);
        return ContractSummary.of(terminated);
    }

    @Transactional
    public ContractSummary asTenant(CurrentActor actor, UUID contractId,
            @Size(max = 2000) String reason) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findByIdForUpdate(contractId).orElseThrow(ContractNotFoundException::new);
        if (!c.tenantUserId().equals(actor.internalUserId())
                && !actor.hasRole(com.fixup.identityaccess.api.Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
        var now = Instant.now();
        var terminated = c.terminateAsTenant(reason, now);
        contracts.update(terminated);
        return ContractSummary.of(terminated);
    }
}
