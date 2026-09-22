package com.fixup.quotations.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.quotations.api.QuotationAccessDeniedException;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationSubmitted;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18: el Fixer responde la solicitud con su oferta. Solo cotiza quien está verificado, que
 * es la regla que module-rules exige invocar antes de dejar trabajar a un técnico.
 */
@Service
public class SubmitQuotation {
    private final Quotations quotations;
    private final RepairRequestDirectory requests;
    private final FixerEligibility eligibility;
    private final ApplicationEventPublisher events;

    SubmitQuotation(Quotations quotations, RepairRequestDirectory requests, FixerEligibility eligibility,
            ApplicationEventPublisher events) {
        this.quotations = quotations;
        this.requests = requests;
        this.eligibility = eligibility;
        this.events = events;
    }

    @Transactional
    public QuotationSummary execute(CurrentActor actor, NewQuotation draft) {
        QuotationAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        var fixerSpecialties = eligibility.specialtiesOf(actor);
        var request = requests.lockForDecision(draft.requestId());
        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request no longer admits quotations");
        }
        if (!fixerSpecialties.contains(request.specialty())) {
            throw new QuotationAccessDeniedException();
        }
        if (request.ownerUserId().equals(actor.internalUserId())) {
            throw new QuotationConflictException("SELF_QUOTATION",
                    "The owner of the request cannot quote it");
        }
        if (quotations.existsByRequestAndFixer(draft.requestId(), actor.internalUserId())) {
            throw new QuotationConflictException("ALREADY_QUOTED",
                    "This fixer already sent a quotation for the request");
        }
        var message = draft.message() == null || draft.message().isBlank() ? null : draft.message().trim();
        var now = Instant.now();
        var quotation = Quotation.submitted(UUID.randomUUID(), draft.requestId(), actor.internalUserId(),
                draft.amount(), draft.estimatedDays(), message, now);
        quotations.create(quotation);
        events.publishEvent(new QuotationSubmitted(quotation.id(), quotation.requestId(),
                quotation.fixerUserId(), request.ownerUserId(), quotation.amount(), now));
        return QuotationSummary.of(quotation);
    }
}
