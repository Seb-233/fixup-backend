package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.RepairRequestAssigned;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        var target = quotations.findById(quotationId)
                .orElseThrow(QuotationNotFoundException::new);
        var requestId = target.requestId();

        var request = requests.lockForDecision(requestId);

        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request was already assigned");
        }

        request.requireOwnedBy(actor.internalUserId());

        var allOffers = quotations.findByRequestForUpdate(requestId);

        var quotation = allOffers.stream()
                .filter(offer -> offer.id().equals(quotationId))
                .findFirst()
                .orElseThrow(QuotationNotFoundException::new);

        if (!quotation.isSubmitted()) {
            throw new QuotationConflictException("QUOTATION_NOT_SUBMITTED",
                    "The quotation is no longer open for decision");
        }

        var now = Instant.now();
        var accepted = quotation.accept(now);
        quotations.update(accepted);

        allOffers.stream()
                .filter(offer -> !offer.id().equals(quotationId))
                .filter(Quotation::isSubmitted)
                .forEach(offer -> quotations.update(offer.reject(now)));

        requests.assign(requestId, accepted.fixerUserId());

        events.publishEvent(new RepairRequestAssigned(requestId, request.ownerUserId(),
                accepted.fixerUserId(), now));
        events.publishEvent(new QuotationAccepted(accepted.id(), accepted.requestId(),
                accepted.fixerUserId(), request.ownerUserId(), accepted.amount(), now));

        return QuotationSummary.of(accepted);
    }
}
