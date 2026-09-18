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

        // 1. Read quotation without locking to find the target requestId.
        var target = quotations.findById(quotationId)
                .orElseThrow(QuotationNotFoundException::new);
        var requestId = target.requestId();

        // 2. Lock repair_requests FIRST by requestId: prevents deadlock between concurrent acceptances.
        var request = requests.lockForDecision(requestId);

        // 3. Verify that the request is still open.
        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request was already assigned");
        }

        // 4. Verify ownership.
        request.requireOwnedBy(actor.internalUserId());

        // 5. Lock all quotations of that request in a consistent order.
        var allOffers = quotations.findByRequestForUpdate(requestId);

        // 6. Verify that the selected quotation is still SUBMITTED.
        var quotation = allOffers.stream()
                .filter(offer -> offer.id().equals(quotationId))
                .findFirst()
                .orElseThrow(QuotationNotFoundException::new);

        if (!quotation.isSubmitted()) {
            throw new QuotationConflictException("QUOTATION_NOT_SUBMITTED",
                    "The quotation is no longer open for decision");
        }

        // 7. Accept the selected quotation.
        var now = Instant.now();
        var accepted = quotation.accept(now);
        quotations.update(accepted);

        // 8. Reject the remaining submitted offers.
        allOffers.stream()
                .filter(offer -> !offer.id().equals(quotationId))
                .filter(Quotation::isSubmitted)
                .forEach(offer -> quotations.update(offer.reject(now)));

        // 9. Assign the request.
        requests.assign(requestId, accepted.fixerUserId());

        // 10. Publish event.
        events.publishEvent(new QuotationAccepted(accepted.id(), accepted.requestId(),
                accepted.fixerUserId(), request.ownerUserId(), accepted.amount(), now));

        return QuotationSummary.of(accepted);
    }
}
