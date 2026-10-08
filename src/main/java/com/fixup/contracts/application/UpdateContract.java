package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractOverlapConflictException;
import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class UpdateContract {
    private final RentalContracts contracts;
    private final CurrentActorProvider currentActorProvider;

    public UpdateContract(RentalContracts contracts, CurrentActorProvider currentActorProvider) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
    }

    public ContractResponse execute(UUID id, UpdateContractRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ContractAccessDeniedException("Actor is not active");
        }

        RentalContract contract = ContractAccess.requireExists(contracts, id);
        contract.requireVisibleTo(actor);

        if (!actor.hasRole(Role.PLATFORM_ADMIN) && !actor.hasRole(Role.REAL_ESTATE_MANAGER)) {
            if (!actor.hasRole(Role.OWNER) || !contract.ownerUserId().equals(actor.internalUserId())) {
                throw new ContractAccessDeniedException("Only owner or admin can update contract");
            }
        }

        if (request.propertyId() != null) {
            contract.setPropertyId(request.propertyId());
        }
        if (request.tenantUserId() != null) {
            contract.setTenantUserId(request.tenantUserId());
        }
        if (request.startDate() != null) {
            contract.setStartDate(request.startDate());
        }
        if (request.endDate() != null) {
            contract.setEndDate(request.endDate());
        }
        if (request.monthlyRentAmount() != null) {
            contract.setMonthlyRentAmount(request.monthlyRentAmount());
        }
        if (request.depositAmount() != null) {
            contract.setDepositAmount(request.depositAmount());
        }
        if (request.paymentDayOfMonth() != null) {
            if (request.paymentDayOfMonth() < 1 || request.paymentDayOfMonth() > 28) {
                throw new IllegalArgumentException("paymentDayOfMonth must be between 1 and 28");
            }
            contract.setPaymentDayOfMonth(request.paymentDayOfMonth());
        }
        if (request.renewalNoticeDays() != null) {
            if (request.renewalNoticeDays() < 0) {
                throw new IllegalArgumentException("renewalNoticeDays must not be negative");
            }
            contract.setRenewalNoticeDays(request.renewalNoticeDays());
        }
        if (request.notes() != null) {
            contract.setNotes(request.notes());
        }

        if ((request.startDate() != null || request.endDate() != null || request.propertyId() != null)) {
            List<UUID> overlapping = contracts.findOverlappingActiveContractIds(
                contract.propertyId(), contract.startDate(), contract.endDate()
            );
            boolean otherOverlap = overlapping.stream().anyMatch(oid -> !oid.equals(id));
            if (otherOverlap) {
                throw new ContractOverlapConflictException(
                    "CONTRACT_OVERLAP",
                    "There is already an active contract overlapping the requested period"
                );
            }
        }

        return ContractResponse.from(contracts.save(contract));
    }
}
