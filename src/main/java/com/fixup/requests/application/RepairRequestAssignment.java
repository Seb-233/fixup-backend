package com.fixup.requests.application;

import com.fixup.requests.api.ProjectRepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestCompletionCommand;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.RequestInvitation;
import com.fixup.requests.domain.RequestInvitations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FR-UC-18: contrato que quotations consume para leer y cerrar una solicitud. La asignación exige
 * una transacción ya abierta por el llamador: aceptar la cotización y asignar la solicitud
 * confirman juntas o no confirman.
 */
@Service
class RepairRequestAssignment implements RepairRequestDirectory {
    private final RepairRequests requests;
    private final RequestInvitations invitations;

    RepairRequestAssignment(RepairRequests requests, RequestInvitations invitations) {
        this.requests = requests;
        this.invitations = invitations;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> invitedFixers(UUID requestId) {
        return invitations.findByRequest(requestId).stream().map(RequestInvitation::fixerUserId)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public RepairRequestSnapshot require(UUID requestId) {
        return requests.findById(requestId).map(request -> request.snapshot())
                .orElseThrow(RepairRequestNotFoundException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectRepairRequestSnapshot> findAll(Collection<UUID> requestIds) {
        return requests.findAllByIds(requestIds).stream()
                .map(request -> new ProjectRepairRequestSnapshot(request.id(), request.specialty(),
                        request.status(), request.assignedFixerUserId(), request.paymentMethod()))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RepairRequestSnapshot lockForDecision(UUID requestId) {
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        return request.snapshot();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assign(UUID requestId, UUID fixerUserId) {
        // Lock the row: two owners accepting different quotations at once must not both win.
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        requests.update(request.assign(fixerUserId, Instant.now()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(RepairRequestCompletionCommand command) {
        var request = requests.findByIdForUpdate(command.requestId())
                .orElseThrow(RepairRequestNotFoundException::new);
        if (request.assignedFixerUserId() == null
                || !request.assignedFixerUserId().equals(command.fixerUserId())
                || !request.ownerUserId().equals(command.ownerUserId())) {
            throw new RepairRequestConflictException("REQUEST_NOT_ASSIGNED",
                    "Only the matching owner and assigned fixer can complete the request");
        }
        if (request.status() == RepairRequestStatus.COMPLETED) {
            return;
        }
        if (request.status() != RepairRequestStatus.ASSIGNED
                && request.status() != RepairRequestStatus.SLA_WARNING
                && request.status() != RepairRequestStatus.SLA_BREACHED) {
            throw new RepairRequestConflictException("REQUEST_NOT_ASSIGNED",
                    "Only an assigned request can be completed");
        }
        requests.update(request.complete(command.completedAt()));
    }
}
