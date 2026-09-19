package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.RepairRequestDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: el tablero comparativo del propietario. Solo el dueño de la solicitud ve las ofertas
 * que recibió; un identificador ajeno no abre las cotizaciones de otro.
 */
@Service
public class ListQuotationsForRequest {
    private final Quotations quotations;
    private final RepairRequestDirectory requests;

    ListQuotationsForRequest(Quotations quotations, RepairRequestDirectory requests) {
        this.quotations = quotations;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<QuotationSummary> execute(CurrentActor actor, UUID requestId) {
        QuotationAccess.requireActive(actor);
        requests.require(requestId).requireOwnedBy(actor.internalUserId());
        return quotations.findByRequest(requestId).stream().map(QuotationSummary::of).toList();
    }
}
