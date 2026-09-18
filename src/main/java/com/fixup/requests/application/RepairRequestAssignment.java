package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: contrato que quotations consume para leer y cerrar una solicitud. La asignación exige
 * una transacción ya abierta por el llamador: aceptar la cotización y asignar la solicitud
 * confirman juntas o no confirman.
 */
@Service
class RepairRequestAssignment implements RepairRequestDirectory {
    private final RepairRequests requests;

    RepairRequestAssignment(RepairRequests requests) {
        this.requests = requests;
    }

    @Override
    @Transactional(readOnly = true)
    public RepairRequestSnapshot require(UUID requestId) {
        return requests.findById(requestId).map(request -> request.snapshot())
                .orElseThrow(RepairRequestNotFoundException::new);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assign(UUID requestId, UUID fixerUserId) {
        // Lock the row: two owners accepting different quotations at once must not both win.
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(RepairRequestNotFoundException::new);
        requests.update(request.assign(fixerUserId, Instant.now()));
    }
}
