package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.requests.api.RepairRequestOpened;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.fixers.api.Specialty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-18: el propietario abre la solicitud que los Fixers verán en su bandeja. */
@Service
public class CreateRepairRequest {
    private final RepairRequests requests;
    private final PropertyDirectory propertyDirectory;
    private final RepairSpecialtyClassifier classifier;
    private final MediaAttachmentService mediaAttachmentService;
    private final ApplicationEventPublisher events;

    CreateRepairRequest(RepairRequests requests, MediaAttachmentService mediaAttachmentService, PropertyDirectory propertyDirectory, RepairSpecialtyClassifier classifier, ApplicationEventPublisher events) {
        this.requests = requests;
        this.propertyDirectory = propertyDirectory;
        this.classifier = classifier;
        this.mediaAttachmentService = mediaAttachmentService;
        this.events = events;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, NewRepairRequest draft) {
        RequestAccess.requireActiveOwner(actor);
        propertyDirectory.requireOwnedBy(draft.propertyId(), actor.internalUserId());
        Specialty specialty = classifier.classify(draft.title(), draft.description());
        var mediaIds = draft.mediaIds() == null ? List.<UUID>of() : draft.mediaIds();
        mediaAttachmentService.attachRepairRequestPhotos(actor.internalUserId(), mediaIds);
        var now = Instant.now();
        var request = RepairRequest.open(UUID.randomUUID(), draft.propertyId(), actor.internalUserId(), specialty,
                draft.title().trim(), draft.description().trim(), mediaIds, draft.urgency(), now);
        requests.create(request);
        events.publishEvent(new RepairRequestOpened(request.id(), request.ownerUserId(),
                request.specialty(), request.title(), request.urgency(), now));
        return RepairRequestSummary.of(request);
    }
}
