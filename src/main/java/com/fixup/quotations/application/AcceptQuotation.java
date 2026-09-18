package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: el propietario elige una cotización. La aceptada, el rechazo de las demás y el cierre
 * de la solicitud ocurren en la misma transacción: nunca queda una solicitud con dos ganadores.
 */
@Service
public class AcceptQuotation {
    private final Quotations quotations;
    private final RepairRequestDirectory requests;
    private final ApplicationEventPublisher events;

    AcceptQuotation(Quotations quotations, RepairRequestDirectory requests,
            ApplicationEventPublisher events) {
        this.quotations = quotations;
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public QuotationSummary execute(CurrentActor actor, UUID quotationId) {
        QuotationAccess.requireActive(actor);
        // The state check and the write must see the same row: lock it before deciding, or two
        // concurrent acceptances both pass requireSubmitted and the second overwrites the first.
        var quotation = quotations.findByIdForUpdate(quotationId)
                .orElseThrow(QuotationNotFoundException::new);
        var request = requests.require(quotation.requestId());
        request.requireOwnedBy(actor.internalUserId());
        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request was already assigned");
        }
        var now = Instant.now();
        var accepted = quotation.accept(now);
        quotations.update(accepted);
        closeLosingOffers(quotation.requestId(), quotationId, now);
        requests.assign(quotation.requestId(), accepted.fixerUserId());
        events.publishEvent(new QuotationAccepted(accepted.id(), accepted.requestId(),
                accepted.fixerUserId(), request.ownerUserId(), accepted.amount(), now));
        return QuotationSummary.of(accepted);
    }

    private void closeLosingOffers(UUID requestId, UUID acceptedId, Instant now) {
        quotations.findByRequestForUpdate(requestId).stream()
                .filter(offer -> !offer.id().equals(acceptedId))
                .filter(Quotation::isSubmitted)
                .forEach(offer -> quotations.update(offer.reject(now)));
    }
}
