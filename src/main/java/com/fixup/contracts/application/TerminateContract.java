package com.fixup.contracts.application;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.api.StatusAuditTrail;
import com.fixup.audit.api.StatusChangeCommand;
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
    private final StatusAuditTrail auditTrail;

    public TerminateContract(RentalContracts contracts, CurrentActorProvider currentActorProvider,
            StatusAuditTrail auditTrail) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
        this.auditTrail = auditTrail;
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

        var previousStatus = contract.status().name();
        contract.terminate(reason);
        var saved = contracts.save(contract);
        auditTrail.record(StatusChangeCommand.user(AuditedResourceType.RENTAL_CONTRACT, saved.id(),
                previousStatus, saved.status().name(), actor.internalUserId(), actor.externalSubject(),
                saved.updatedAt()));
        return ContractResponse.from(saved);
    }
}
