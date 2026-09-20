package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.requests.api.RepairRequestOpened;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateRepairRequest {
    private final RepairRequests requests;
    private final MediaAttachmentService mediaAttachmentService;
    private final ApplicationEventPublisher events;

    CreateRepairRequest(RepairRequests requests, MediaAttachmentService mediaAttachmentService,
            ApplicationEventPublisher events) {
        this.requests = requests;
        this.mediaAttachmentService = mediaAttachmentService;
        this.events = events;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, NewRepairRequest draft) {
        RequestAccess.requireActiveRequester(actor);
        var mediaIds = draft.mediaIds() == null ? List.<UUID>of() : draft.mediaIds();
        mediaAttachmentService.attachRepairRequestPhotos(actor.internalUserId(), mediaIds);
        var urgency = draft.urgency() == null ? RepairRequestUrgency.MEDIUM : draft.urgency();
        var now = Instant.now();
        var request = RepairRequest.open(UUID.randomUUID(), actor.internalUserId(), draft.specialty(),
                draft.title().trim(), draft.description().trim(), mediaIds, urgency, now);
        requests.create(request);
        events.publishEvent(new RepairRequestOpened(request.id(), request.ownerUserId(),
                request.specialty(), request.title(), request.urgency(), request.createdAt()));
        return RepairRequestSummary.of(request);
    }
}
