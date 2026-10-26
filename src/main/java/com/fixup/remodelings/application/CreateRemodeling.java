package com.fixup.remodelings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.payments.api.OwnerDebtDirectory;
import com.fixup.remodelings.api.RemodelingConflictException;
import com.fixup.remodelings.domain.RemodelingProject;
import com.fixup.remodelings.domain.RemodelingProjects;
import com.fixup.remodelings.domain.RemodelingRequirement;
import com.fixup.requests.api.NewProjectRepairRequest;
import com.fixup.requests.api.RepairRequestCreation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateRemodeling {
    private final RemodelingProjects projects;
    private final PropertyDirectory properties;
    private final RepairRequestCreation requestCreation;
    private final RemodelingViewAssembler views;
    private final Clock clock;
    private final OwnerDebtDirectory debts;

    CreateRemodeling(RemodelingProjects projects, PropertyDirectory properties,
            RepairRequestCreation requestCreation, RemodelingViewAssembler views,
            OwnerDebtDirectory debts, Clock clock) {
        this.projects = projects;
        this.properties = properties;
        this.requestCreation = requestCreation;
        this.views = views;
        this.clock = clock;
        this.debts = debts;
    }

    @Transactional
    public RemodelingProjectDetail execute(CurrentActor actor, NewRemodeling draft) {
        RemodelingAccess.requireActiveOwner(actor);
        if (debts.hasOutstandingDebt(actor.internalUserId())) {
            throw new RemodelingConflictException("OWNER_HAS_OUTSTANDING_DEBT",
                    "Pay outstanding debt before creating a remodeling project");
        }
        if (draft.requirements().isEmpty()) {
            throw new RemodelingConflictException("REQUIREMENTS_REQUIRED",
                    "A remodeling project requires at least one requirement");
        }
        properties.requireOwnedBy(draft.propertyId(), actor.internalUserId());

        UUID projectId = UUID.randomUUID();
        var requirements = new ArrayList<RemodelingRequirement>(draft.requirements().size());
        for (int position = 0; position < draft.requirements().size(); position++) {
            var requirement = draft.requirements().get(position);
            var request = requestCreation.open(actor, new NewProjectRepairRequest(
                    draft.propertyId(), requirement.title(), requirement.description(),
                    requirement.mediaIds(), requirement.urgencyLevel(), requirement.paymentMethod()));
            requirements.add(new RemodelingRequirement(UUID.randomUUID(), projectId,
                    request.requestId(), position));
        }
        Instant now = Instant.now(clock);
        var project = RemodelingProject.create(projectId, draft.propertyId(),
                actor.internalUserId(), draft.title(), draft.description(), requirements, draft.plan(), now);
        projects.create(project);
        return views.detail(project, actor);
    }
}
