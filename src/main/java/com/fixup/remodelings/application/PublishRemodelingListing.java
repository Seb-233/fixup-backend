package com.fixup.remodelings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyListingPublisher;
import com.fixup.remodelings.api.RemodelingConflictException;
import com.fixup.remodelings.api.RemodelingNotFoundException;
import com.fixup.remodelings.domain.RemodelingProjects;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-26: cuando todas las necesidades de la remodelación pre-arriendo quedaron terminadas, el
 * propietario publica el inmueble en arriendo con el canon objetivo en un solo paso.
 */
@Service
public class PublishRemodelingListing {
    private final RemodelingProjects projects;
    private final RemodelingViewAssembler views;
    private final PropertyListingPublisher listings;
    private final Clock clock;

    PublishRemodelingListing(RemodelingProjects projects, RemodelingViewAssembler views,
            PropertyListingPublisher listings, Clock clock) {
        this.projects = projects;
        this.views = views;
        this.listings = listings;
        this.clock = clock;
    }

    @Transactional
    public RemodelingProjectDetail execute(CurrentActor actor, UUID projectId) {
        RemodelingAccess.requireActiveOwner(actor);
        var project = projects.findById(projectId).orElseThrow(() -> new RemodelingNotFoundException(projectId));
        if (!project.ownerUserId().equals(actor.internalUserId())) {
            throw new RemodelingNotFoundException(projectId);
        }
        var detail = views.detail(project, actor);
        if (!detail.readyToPublish()) {
            throw new RemodelingConflictException("NOT_READY_TO_PUBLISH",
                    "Every requirement must be completed before publishing the rental listing");
        }
        listings.publishForRent(actor, project.propertyId(), project.plan().targetRent());
        var published = project.markListingPublished(Instant.now(clock));
        projects.updatePlan(published);
        return views.detail(published, actor);
    }
}
