package com.fixup.requests.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListOpenRepairRequests {
    private final RepairRequests requests;
    private final FixerEligibility eligibility;

    public ListOpenRepairRequests(RepairRequests requests, FixerEligibility eligibility) {
        this.requests = requests;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public List<RepairRequest> execute(CurrentActor actor) {
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
            return List.of();
        }

        var city = eligibility.cityOf(actor);
        List<RepairRequest> requestsList = requests.findOpenBySpecialtiesAndCity(specialties, city);

        return requestsList.stream()
                .filter(request -> !request.ownerUserId().equals(actor.internalUserId()))
                .toList();
    }
}
