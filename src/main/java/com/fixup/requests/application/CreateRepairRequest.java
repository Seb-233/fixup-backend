package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.notifications.api.RequestNotifications;
import com.fixup.payments.api.OwnerDebtDirectory;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.api.NewProjectRepairRequest;
import com.fixup.requests.api.ProjectRepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestCreation;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.fixers.api.Specialty;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateRepairRequest implements RepairRequestCreation {
    private final RepairRequests requests;
    private final PropertyDirectory propertyDirectory;
    private final RepairSpecialtyClassifier classifier;
    private final MediaAttachmentService mediaAttachmentService;
    private final RequestNotifications notifications;
    private final Clock clock;
    private final OwnerDebtDirectory debts;

    CreateRepairRequest(RepairRequests requests, MediaAttachmentService mediaAttachmentService, PropertyDirectory propertyDirectory, RepairSpecialtyClassifier classifier,
                        RequestNotifications notifications, OwnerDebtDirectory debts, Clock clock) {
        this.requests = requests;
        this.propertyDirectory = propertyDirectory;
        this.classifier = classifier;
        this.mediaAttachmentService = mediaAttachmentService;
        this.notifications = notifications;
        this.clock = clock;
        this.debts = debts;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, NewRepairRequest draft) {
        RequestAccess.requireActiveOwner(actor);
        if (debts.hasOutstandingDebt(actor.internalUserId())) {
            throw new RepairRequestConflictException("OWNER_HAS_OUTSTANDING_DEBT",
                    "Pay outstanding debt before creating another repair request");
        }
        var property = propertyDirectory.requireOwnedBy(draft.propertyId(), actor.internalUserId());
        Specialty specialty = classifier.classify(draft.title(), draft.description());
        var mediaIds = draft.mediaIds() == null ? List.<UUID>of() : draft.mediaIds();
        mediaAttachmentService.attachRepairRequestPhotos(actor.internalUserId(), mediaIds);
        UrgencyLevel urgency = draft.urgencyLevel() == null ? UrgencyLevel.MEDIUM : draft.urgencyLevel();
        var request = RepairRequest.open(UUID.randomUUID(), draft.propertyId(), property.city(), actor.internalUserId(), specialty,
                draft.title().trim(), draft.description().trim(), mediaIds, urgency,
                draft.paymentMethod(), Instant.now(clock));
        requests.create(request);

        if (urgency == UrgencyLevel.URGENT) {
            notifications.urgentRequestCreated(request.id(), actor.internalUserId(), request.title(), Instant.now(clock));
        }

        return RepairRequestSummary.of(request);
    }

    @Override
    public ProjectRepairRequestSnapshot open(CurrentActor actor, NewProjectRepairRequest draft) {
        var summary = execute(actor, new NewRepairRequest(draft.propertyId(), draft.title(),
                draft.description(), draft.mediaIds(), draft.urgencyLevel(), draft.paymentMethod()));
        return new ProjectRepairRequestSnapshot(summary.id(), summary.specialty(), summary.status(),
                summary.assignedFixerUserId(), summary.paymentMethod());
    }
}
