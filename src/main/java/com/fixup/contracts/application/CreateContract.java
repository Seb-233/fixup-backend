package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractCreated;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.api.ContractOverlapConflictException;
import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertySnapshot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CreateContract {
    private final RentalContracts contracts;
    private final CurrentActorProvider currentActorProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final PropertyDirectory properties;
    private final UserDirectory users;

    public CreateContract(RentalContracts contracts, CurrentActorProvider currentActorProvider,
                          ApplicationEventPublisher eventPublisher, PropertyDirectory properties,
                          UserDirectory users) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.users = users;
    }

    public ContractResponse execute(CreateContractRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        ContractAccess.requireCanCreate(actor);

        UUID ownerUserId = resolveOwner(actor, request.propertyId());
        if (!users.hasActiveRole(request.tenantUserId(), Role.TENANT)) {
            throw new ContractAccessDeniedException("The tenant must be an active user with the TENANT role");
        }
        if (request.tenantUserId().equals(ownerUserId)) {
            throw new ContractAccessDeniedException("The owner cannot rent their own property");
        }

        ListOverlapHelper.assertNoOverlap(contracts, request.propertyId(), request.startDate(), request.endDate());

        RentalContract contract = RentalContract.create(
            request.propertyId(),
            ownerUserId,
            request.tenantUserId(),
            request.startDate(),
            request.endDate(),
            request.monthlyRentAmount(),
            request.depositAmount(),
            request.paymentDayOfMonth(),
            request.notes()
        );

        RentalContract saved = contracts.save(contract);
        eventPublisher.publishEvent(new ContractCreated(
            saved.id(), saved.propertyId(), saved.ownerUserId(), saved.tenantUserId()
        ));

        return ContractResponse.from(saved);
    }

    /**
     * FR-UC-14: el contrato lo firma el propietario del inmueble. Si lo registra su administrador
     * inmobiliario (FR-UC-11), el contrato igual queda a nombre del propietario.
     */
    private UUID resolveOwner(CurrentActor actor, UUID propertyId) {
        PropertySnapshot property;
        try {
            property = properties.require(propertyId);
        } catch (PropertyNotFoundException notVisible) {
            throw new ContractAccessDeniedException("The property does not exist or is not yours");
        }
        var userId = actor.internalUserId();
        if (property.ownerUserId().equals(userId) || property.isManagedBy(userId) || actor.hasRole(Role.PLATFORM_ADMIN)) {
            return property.ownerUserId();
        }
        throw new ContractAccessDeniedException("The property does not exist or is not yours");
    }
}

final class ContractAccess {
    private ContractAccess() {}

    static void requireCanCreate(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ContractAccessDeniedException("Actor is not active");
        }
        if (!actor.hasRole(Role.OWNER) && !actor.hasRole(Role.REAL_ESTATE_MANAGER) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException("Only OWNER, REAL_ESTATE_MANAGER or PLATFORM_ADMIN can create contracts");
        }
    }

    static RentalContract requireExists(RentalContracts contracts, UUID id) {
        return contracts.findById(id)
            .orElseThrow(() -> new ContractNotFoundException(id));
    }
}

final class ListOverlapHelper {
    private ListOverlapHelper() {}

    static void assertNoOverlap(RentalContracts contracts, UUID propertyId,
                                LocalDate start, LocalDate end) {
        List<UUID> overlapping = contracts.findOverlappingActiveContractIds(propertyId, start, end);
        if (!overlapping.isEmpty()) {
            throw new ContractOverlapConflictException(
                "CONTRACT_OVERLAP",
                "There is already an active contract for property " + propertyId +
                    " overlapping the period " + start + " to " + end
            );
        }
    }
}
