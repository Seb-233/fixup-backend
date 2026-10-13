package com.fixup.requests.application;

import com.fixup.contracts.api.TenancyDirectory;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.api.RequestNotifications;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertySnapshot;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-04 y FR-UC-08: abre una solicitud sobre un inmueble. La abre su propietario, el
 * administrador inmobiliario que lo gestiona o el arrendatario con contrato vigente; en los tres
 * casos la decisión y el pago siguen siendo del propietario (o de su administrador).
 */
@Service
public class CreateRepairRequest implements RepairRequestCreation {
    static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");

    private final RepairRequests requests;
    private final PropertyDirectory propertyDirectory;
    private final TenancyDirectory tenancy;
    private final RepairSpecialtyClassifier classifier;
    private final MediaAttachmentService mediaAttachmentService;
    private final RequestNotifications notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CreateRepairRequest(RepairRequests requests, MediaAttachmentService mediaAttachmentService,
                        PropertyDirectory propertyDirectory, TenancyDirectory tenancy,
                        RepairSpecialtyClassifier classifier, RequestNotifications notifications,
                        ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.propertyDirectory = propertyDirectory;
        this.tenancy = tenancy;
        this.classifier = classifier;
        this.mediaAttachmentService = mediaAttachmentService;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public RepairRequestSummary execute(CurrentActor actor, NewRepairRequest draft) {
        RequestAccess.requireActiveRequester(actor);
        var property = requireRelationship(actor, draft.propertyId());
        Specialty specialty = classifier.classify(draft.title(), draft.description());
        var mediaIds = draft.mediaIds() == null ? List.<UUID>of() : draft.mediaIds();
        mediaAttachmentService.attachRepairRequestPhotos(actor.internalUserId(), mediaIds);
        UrgencyLevel urgency = draft.urgencyLevel() == null ? UrgencyLevel.MEDIUM : draft.urgencyLevel();
        var context = new RepairRequest.PropertyContext(property.id(), property.city(), property.name(),
                property.address(), property.ownerUserId(), property.managerUserId());
        var request = RepairRequest.open(UUID.randomUUID(), context, actor.internalUserId(), specialty,
                draft.title().trim(), draft.description().trim(), mediaIds, urgency, Instant.now(clock));
        requests.create(request);

        if (urgency == UrgencyLevel.URGENT) {
            notifications.urgentRequestCreated(request.id(), request.ownerUserId(), request.title(), Instant.now(clock));
        }
        notifyDecisionMakers(request);
        return RepairRequestSummary.of(request);
    }

    @Override
    public ProjectRepairRequestSnapshot open(CurrentActor actor, NewProjectRepairRequest draft) {
        var summary = execute(actor, new NewRepairRequest(draft.propertyId(), draft.title(),
                draft.description(), draft.mediaIds(), draft.urgencyLevel()));
        return new ProjectRepairRequestSnapshot(summary.id(), summary.specialty(), summary.status(),
                summary.assignedFixerUserId());
    }

    /**
     * Ninguna relación con el inmueble responde 404, igual que antes: a quien no tiene vínculo con
     * él no se le confirma que exista.
     */
    private PropertySnapshot requireRelationship(CurrentActor actor, UUID propertyId) {
        var userId = actor.internalUserId();
        var property = propertyDirectory.require(propertyId);
        if (actor.hasRole(Role.OWNER) && property.ownerUserId().equals(userId)) {
            return property;
        }
        if (actor.hasRole(Role.REAL_ESTATE_MANAGER) && property.isManagedBy(userId)) {
            return property;
        }
        if (actor.hasRole(Role.TENANT)
                && tenancy.isActiveTenant(userId, propertyId, LocalDate.now(clock.withZone(COLOMBIA)))) {
            return property;
        }
        throw new PropertyNotFoundException(propertyId);
    }

    private void notifyDecisionMakers(RepairRequest request) {
        var reporter = request.requestedByUserId();
        var body = "\"" + request.title() + "\" en " + (request.propertyName() == null ? "tu inmueble"
                : request.propertyName()) + ". Los técnicos de la especialidad ya pueden cotizar.";
        if (!request.ownerUserId().equals(reporter)) {
            events.publishEvent(NotificationRequested.of(request.ownerUserId(), NotificationType.REQUEST_REPORTED,
                    "Nueva falla reportada en tu inmueble", body, "/requests/" + request.id(), request.id(), "REQUEST"));
        }
        if (request.managerUserId() != null && !request.managerUserId().equals(reporter)) {
            events.publishEvent(NotificationRequested.of(request.managerUserId(), NotificationType.REQUEST_REPORTED,
                    "Nueva solicitud en un inmueble que administras", body, "/management/requests/" + request.id(),
                    request.id(), "REQUEST"));
        }
    }
}
