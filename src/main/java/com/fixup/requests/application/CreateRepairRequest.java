package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-18: el propietario abre la solicitud que los Fixers verán en su bandeja. */
@Service
public class CreateRepairRequest {
    private final RepairRequests requests;
    private final MediaAttachmentService mediaAttachmentService;

    CreateRepairRequest(RepairRequests requests, MediaAttachmentService mediaAttachmentService) {
        this.requests = requests;
        this.mediaAttachmentService = mediaAttachmentService;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, NewRepairRequest draft) {
        RequestAccess.requireActiveRequester(actor);
        var mediaIds = draft.mediaIds() == null ? List.<UUID>of() : draft.mediaIds();
        mediaAttachmentService.attachRepairRequestPhotos(actor.internalUserId(), mediaIds);
        var request = RepairRequest.open(UUID.randomUUID(), actor.internalUserId(), draft.specialty(),
                draft.title().trim(), draft.description().trim(), mediaIds, Instant.now());
        requests.create(request);
        return RepairRequestSummary.of(request);
    }
}
