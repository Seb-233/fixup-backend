package com.fixup.quotations.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
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

    SubmitQuotation(Quotations quotations, RepairRequestDirectory requests, FixerEligibility eligibility) {
        this.quotations = quotations;
        this.requests = requests;
        this.eligibility = eligibility;
    }

    @Transactional
    public QuotationSummary execute(CurrentActor actor, NewQuotation draft) {
        QuotationAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        var request = requests.require(draft.requestId());
        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request no longer admits quotations");
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
        var quotation = Quotation.submitted(UUID.randomUUID(), draft.requestId(), actor.internalUserId(),
                draft.amount(), draft.estimatedDays(), message, Instant.now());
        quotations.create(quotation);
        return QuotationSummary.of(quotation);
    }
}
