package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class TerminateContract {
    private final RentalContracts contracts;
    private final CurrentActorProvider currentActorProvider;

    public TerminateContract(RentalContracts contracts, CurrentActorProvider currentActorProvider) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
    }

    public ContractResponse execute(UUID id, String reason) {
        CurrentActor actor = currentActorProvider.currentActor();
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ContractAccessDeniedException("Actor is not active");
        }

        RentalContract contract = ContractAccess.requireExists(contracts, id);
        contract.requireVisibleTo(actor);

        if (!actor.hasRole(Role.PLATFORM_ADMIN) && !actor.hasRole(Role.REAL_ESTATE_MANAGER)) {
            if (!actor.hasRole(Role.OWNER) || !contract.ownerUserId().equals(actor.internalUserId())) {
                throw new ContractAccessDeniedException("Only owner or admin can terminate contract");
            }
        }

        contract.terminate(reason);
        return ContractResponse.from(contracts.save(contract));
    }
}
