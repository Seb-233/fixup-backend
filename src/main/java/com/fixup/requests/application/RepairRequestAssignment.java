package com.fixup.requests.application;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.api.StatusAuditTrail;
import com.fixup.audit.api.StatusChangeCommand;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-UC-18: contrato que quotations consume para leer y cerrar una solicitud. La asignación exige
 * una transacción ya abierta por el llamador: aceptar la cotización y asignar la solicitud
 * confirman juntas o no confirman.
 */
@Service
class RepairRequestAssignment implements RepairRequestDirectory {
    private final RepairRequests requests;
    private final RequestInvitations invitations;
    private final StatusAuditTrail auditTrail;

    @Autowired
    RepairRequestAssignment(RepairRequests requests, RequestInvitations invitations,
            StatusAuditTrail auditTrail) {
        this.requests = requests;
        this.invitations = invitations;
        this.auditTrail = auditTrail;
    }

    RepairRequestAssignment(RepairRequests requests, RequestInvitations invitations) {
        this(requests, invitations, command -> { });
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
    @Transactional(readOnly = true)
    public Map<UUID, String> titles(Collection<UUID> requestIds) {
        return requests.findAllByIds(requestIds).stream()
                .filter(request -> Objects.nonNull(request.title()))
                .collect(Collectors.toMap(request -> request.id(), request -> request.title()));
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
    public void assign(UUID requestId, UUID fixerUserId, UUID changedByUserId, String changedBySubject,
            Instant changedAt) {
        // Lock the row: two owners accepting different quotations at once must not both win.
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        var updated = request.assign(fixerUserId, changedAt);
        requests.update(updated);
        auditTrail.record(StatusChangeCommand.user(AuditedResourceType.REPAIR_REQUEST, updated.id(),
                request.status().name(), updated.status().name(), changedByUserId, changedBySubject,
                changedAt));
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
        var completed = request.complete(command.completedAt());
        requests.update(completed);
        auditTrail.record(StatusChangeCommand.user(AuditedResourceType.REPAIR_REQUEST, completed.id(),
                request.status().name(), completed.status().name(), command.changedByUserId(),
                command.changedBySubject(), command.completedAt()));
    }
}
