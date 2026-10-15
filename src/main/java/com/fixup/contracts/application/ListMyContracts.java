package com.fixup.contracts.application;

import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ListMyContracts {
    private final RentalContracts contracts;
    private final CurrentActorProvider currentActorProvider;

    public ListMyContracts(RentalContracts contracts, CurrentActorProvider currentActorProvider) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
    }

    public List<ContractResponse> execute() {
        CurrentActor actor = currentActorProvider.currentActor();
        List<RentalContract> result;

        if (actor.hasRole(Role.PLATFORM_ADMIN) || actor.hasRole(Role.REAL_ESTATE_MANAGER)) {
            result = contracts.findByOwnerOrTenant(actor.internalUserId(), actor.internalUserId());
        } else if (actor.hasRole(Role.OWNER)) {
            result = contracts.findByOwnerOrTenant(actor.internalUserId(), actor.internalUserId())
                .stream()
                .filter(c -> c.ownerUserId().equals(actor.internalUserId()))
                .toList();
        } else if (actor.hasRole(Role.TENANT)) {
            result = contracts.findByOwnerOrTenant(actor.internalUserId(), actor.internalUserId())
                .stream()
                .filter(c -> c.tenantUserId().equals(actor.internalUserId()))
                .toList();
        } else {
            result = List.of();
        }

        return result.stream().map(ContractResponse::from).toList();
    }

    public List<ContractResponse> executeExpiring(int days) {
        CurrentActor actor = currentActorProvider.currentActor();
        LocalDate today = LocalDate.now();
        List<RentalContract> expiring = contracts.findExpiringWithinDays(days, today);

        return expiring.stream()
            .filter(c -> {
                try {
                    c.requireVisibleTo(actor);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            })
            .map(ContractResponse::from)
            .toList();
    }
}
