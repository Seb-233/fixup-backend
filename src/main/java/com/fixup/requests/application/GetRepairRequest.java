package com.fixup.requests.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: el detalle que evalúa el usuario autorizado.
 * Solo responde al propietario, admin autorizado, fixer asignado, o fixer verificado y compatible mientras esté OPEN.
 */
@Service
public class GetRepairRequest {
    private final RepairRequests requests;
    private final FixerEligibility eligibility;

    public GetRepairRequest(RepairRequests requests, FixerEligibility eligibility) {
        this.requests = requests;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public RepairRequest execute(CurrentActor actor, UUID requestId) {
        var request = requests.findById(requestId).orElseThrow(RepairRequestNotFoundException::new);
        requireAuthorized(actor, request);
        return request;
    }

    private void requireAuthorized(CurrentActor actor, RepairRequest request) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RepairRequestAccessDeniedException();
        }
        var userId = actor.internalUserId();
        if (request.ownerUserId().equals(userId)) {
            return;
        }
        if (actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        if (request.assignedFixerUserId() != null && request.assignedFixerUserId().equals(userId)) {
            return;
        }
        if (request.isOpen() && actor.hasRole(Role.FIXER)) {
            try {
                eligibility.requireVerified(actor);
                var specialties = eligibility.specialtiesOf(actor);
                if (specialties != null && specialties.contains(request.specialty())) {
                    return;
                }
            } catch (FixerNotEligibleException ex) {
                throw new RepairRequestAccessDeniedException();
            }
        }
        throw new RepairRequestAccessDeniedException();
    }
}
