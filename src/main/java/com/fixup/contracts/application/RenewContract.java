package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional
public class RenewContract {
    private final RentalContracts contracts;
    private final CurrentActorProvider currentActorProvider;
    private final ApplicationEventPublisher eventPublisher;

    public RenewContract(RentalContracts contracts, CurrentActorProvider currentActorProvider,
                         ApplicationEventPublisher eventPublisher) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
        this.eventPublisher = eventPublisher;
    }

    public ContractResponse execute(UUID id, LocalDate newEndDate) {
        CurrentActor actor = currentActorProvider.currentActor();
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ContractAccessDeniedException("Actor is not active");
        }

        RentalContract contract = ContractAccess.requireExists(contracts, id);
        contract.requireVisibleTo(actor);

        if (!actor.hasRole(Role.PLATFORM_ADMIN) && !actor.hasRole(Role.REAL_ESTATE_MANAGER)) {
            if (!actor.hasRole(Role.OWNER) || !contract.ownerUserId().equals(actor.internalUserId())) {
                throw new ContractAccessDeniedException("Only owner or admin can renew contract");
            }
        }

        contract.renew(newEndDate);
        RentalContract saved = contracts.save(contract);

        eventPublisher.publishEvent(new com.fixup.contracts.api.ContractCreated(
            saved.id(), saved.propertyId(), saved.ownerUserId(), saved.tenantUserId()
        ));

        return ContractResponse.from(saved);
    }
}
