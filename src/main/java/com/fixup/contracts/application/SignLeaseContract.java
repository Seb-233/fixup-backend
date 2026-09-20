package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractConflictException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.api.LeaseContractSigned;
import com.fixup.contracts.domain.Contracts;
import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignLeaseContract {
    private final Contracts contracts;
    private final ApplicationEventPublisher events;

    SignLeaseContract(Contracts contracts, ApplicationEventPublisher events) {
        this.contracts = contracts;
        this.events = events;
    }

    @Transactional
    public ContractSummary asTenant(CurrentActor actor, UUID contractId) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findByIdForUpdate(contractId).orElseThrow(ContractNotFoundException::new);
        if (!c.tenantUserId().equals(actor.internalUserId())) {
            throw new ContractAccessDeniedException();
        }
        var now = Instant.now();
        var signed = c.signAsTenant(actor.internalUserId(), now).activateIfStartDateReached(now);
        contracts.update(signed);
        events.publishEvent(new LeaseContractSigned(signed.id(), signed.ownerUserId(),
                signed.tenantUserId(), actor.internalUserId(), now));
        return ContractSummary.of(signed);
    }

    @Transactional
    public ContractSummary asOwner(CurrentActor actor, UUID contractId) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findByIdForUpdate(contractId).orElseThrow(ContractNotFoundException::new);
        if (!c.ownerUserId().equals(actor.internalUserId())
                && !(c.realEstateManagerUserId() != null
                        && c.realEstateManagerUserId().equals(actor.internalUserId()))) {
            throw new ContractAccessDeniedException();
        }
        var now = Instant.now();
        var signed = c.signAsOwner(actor.internalUserId(), now).activateIfStartDateReached(now);
        contracts.update(signed);
        events.publishEvent(new LeaseContractSigned(signed.id(), signed.ownerUserId(),
                signed.tenantUserId(), actor.internalUserId(), now));
        return ContractSummary.of(signed);
    }

    @Transactional
    public ContractSummary sendForTenantSignature(CurrentActor actor, UUID contractId) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findByIdForUpdate(contractId).orElseThrow(ContractNotFoundException::new);
        if (!c.ownerUserId().equals(actor.internalUserId())
                && !(c.realEstateManagerUserId() != null
                        && c.realEstateManagerUserId().equals(actor.internalUserId()))
                && !actor.hasRole(com.fixup.identityaccess.api.Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
        var sent = c.sendForTenantSignature();
        contracts.update(sent);
        return ContractSummary.of(sent);
    }
}
