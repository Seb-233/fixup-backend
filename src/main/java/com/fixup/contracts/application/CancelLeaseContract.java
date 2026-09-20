package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.api.LeaseContractCancelled;
import com.fixup.contracts.domain.Contracts;
import com.fixup.identityaccess.api.CurrentActor;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelLeaseContract {
    private final Contracts contracts;
    private final ApplicationEventPublisher events;

    CancelLeaseContract(Contracts contracts, ApplicationEventPublisher events) {
        this.contracts = contracts;
        this.events = events;
    }

    @Transactional
    public ContractSummary execute(CurrentActor actor, UUID contractId,
            @Size(max = 2000) String reason) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findByIdForUpdate(contractId).orElseThrow(ContractNotFoundException::new);
        var isOwnerOrManager = c.ownerUserId().equals(actor.internalUserId())
                || (c.realEstateManagerUserId() != null
                        && c.realEstateManagerUserId().equals(actor.internalUserId()));
        var isTenant = c.tenantUserId().equals(actor.internalUserId());
        if (!isOwnerOrManager && !isTenant
                && !actor.hasRole(com.fixup.identityaccess.api.Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
        var now = Instant.now();
        var cancelled = c.cancel(reason, now);
        contracts.update(cancelled);
        events.publishEvent(new LeaseContractCancelled(cancelled.id(), cancelled.ownerUserId(),
                cancelled.tenantUserId(), actor.internalUserId(), reason, now));
        return ContractSummary.of(cancelled);
    }
}
