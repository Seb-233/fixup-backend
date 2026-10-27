package com.fixup.requests.application;

import com.fixup.fixers.api.FixerDirectory;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.RequestInvitation;
import com.fixup.requests.domain.RequestInvitations;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-11: el administrador inmobiliario gestiona las solicitudes de los inmuebles a su cargo:
 * las ve en un solo tablero e invita a cotizar a los técnicos que prefiera. Los técnicos sugeridos
 * los calcula jobs, que es quien conoce la carga de trabajo de cada uno.
 */
@Service
public class ManageMaintenanceRequests {
    static final int MAX_INVITATIONS_PER_CALL = 5;

    private final RepairRequests requests;
    private final RequestInvitations invitations;
    private final FixerDirectory fixers;
    private final ApplicationEventPublisher events;

    ManageMaintenanceRequests(RepairRequests requests, RequestInvitations invitations, FixerDirectory fixers,
            ApplicationEventPublisher events) {
        this.requests = requests;
        this.invitations = invitations;
        this.fixers = fixers;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<RepairRequestSummary> board(CurrentActor actor) {
        requireManagerOrAdmin(actor);
        return requests.findByManager(actor.internalUserId()).stream().map(RepairRequestSummary::of).toList();
    }

    @Transactional
    public List<UUID> invite(CurrentActor actor, UUID requestId, List<UUID> fixerUserIds, String note) {
        var request = requireDecidable(actor, requestId);
        if (!request.isOpen()) {
            throw new RepairRequestConflictException("REQUEST_NOT_OPEN", "Only an open request accepts invitations");
        }
        if (fixerUserIds == null || fixerUserIds.isEmpty() || fixerUserIds.size() > MAX_INVITATIONS_PER_CALL) {
            throw new IllegalArgumentException("Invite between 1 and " + MAX_INVITATIONS_PER_CALL + " fixers at a time");
        }
        var eligible = fixers.verified(request.specialty(), null).stream()
                .map(FixerDirectory.FixerListing::userId)
                .collect(java.util.stream.Collectors.toSet());
        var trimmedNote = note == null || note.isBlank() ? null : note.trim();
        if (trimmedNote != null && trimmedNote.length() > 500) {
            throw new IllegalArgumentException("note must be at most 500 characters");
        }
        Set<UUID> created = new LinkedHashSet<>();
        var now = Instant.now();
        for (UUID fixerUserId : new LinkedHashSet<>(fixerUserIds)) {
            if (!eligible.contains(fixerUserId)) {
                throw new RepairRequestConflictException("FIXER_NOT_ELIGIBLE",
                        "Only verified fixers of the request's specialty can be invited");
            }
            if (invitations.exists(requestId, fixerUserId)) {
                continue;
            }
            invitations.create(new RequestInvitation(UUID.randomUUID(), requestId, fixerUserId,
                    actor.internalUserId(), trimmedNote, now));
            created.add(fixerUserId);
            events.publishEvent(NotificationRequested.of(fixerUserId, NotificationType.REQUEST_INVITATION,
                    "Te invitaron a cotizar",
                    "\"" + request.title() + "\" en " + request.propertyCity()
                            + (trimmedNote == null ? "." : ". Nota: " + trimmedNote),
                    "/requests/" + requestId, requestId, "REQUEST"));
        }
        return List.copyOf(created);
    }

    private RepairRequest requireDecidable(CurrentActor actor, UUID requestId) {
        requireManagerOrAdmin(actor);
        var request = requests.findById(requestId).orElseThrow(RepairRequestNotFoundException::new);
        if (!request.isDecidedBy(actor.internalUserId()) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new RepairRequestAccessDeniedException();
        }
        return request;
    }

    private static void requireManagerOrAdmin(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RepairRequestAccessDeniedException();
        }
        if (!actor.hasRole(Role.REAL_ESTATE_MANAGER) && !actor.hasRole(Role.OWNER)
                && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

}
