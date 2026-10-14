package com.fixup.requests.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.RequestInvitations;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: la bandeja del técnico. Muestra las solicitudes abiertas de sus especialidades en su
 * ciudad y, por encima de todas, aquellas a las que un administrador inmobiliario lo invitó
 * (FR-UC-11), aunque estén en otra ciudad.
 */
@Service
public class ListOpenRepairRequests {
    private final RepairRequests requests;
    private final FixerEligibility eligibility;
    private final RequestInvitations invitations;

    public ListOpenRepairRequests(RepairRequests requests, FixerEligibility eligibility, RequestInvitations invitations) {
        this.requests = requests;
        this.eligibility = eligibility;
        this.invitations = invitations;
    }

    @Transactional(readOnly = true)
    public List<RepairRequest> execute(CurrentActor actor) {
        return inbox(actor).requests();
    }

    @Transactional(readOnly = true)
    public Inbox inbox(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new RepairRequestAccessDeniedException();
        }
        try {
            eligibility.requireVerified(actor);
        } catch (FixerNotEligibleException ex) {
            throw new RepairRequestAccessDeniedException();
        }

        var specialties = eligibility.specialtiesOf(actor);
        if (specialties == null || specialties.isEmpty()) {
            return new Inbox(List.of(), Set.of());
        }

        var invited = invitations.invitedRequestIds(actor.internalUserId());
        var byId = new LinkedHashMap<UUID, RepairRequest>();
        requests.findAllByIds(invited).stream()
                .filter(RepairRequest::isOpen)
                .sorted(Comparator.comparing(RepairRequest::createdAt).reversed())
                .forEach(request -> byId.put(request.id(), request));
        var city = eligibility.cityOf(actor);
        requests.findOpenBySpecialtiesAndCity(specialties, city)
                .forEach(request -> byId.putIfAbsent(request.id(), request));

        var visible = new ArrayList<RepairRequest>();
        byId.values().stream()
                .filter(request -> !request.ownerUserId().equals(actor.internalUserId()))
                .forEach(visible::add);
        return new Inbox(List.copyOf(visible), invited);
    }

    public record Inbox(List<RepairRequest> requests, Set<UUID> invitedRequestIds) {
    }
}
