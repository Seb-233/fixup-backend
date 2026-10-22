package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.RepairRequestDirectory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-18: el propietario rechaza explícitamente una cotización sin aceptar otra.
 */
@Service
public class RejectQuotation {
    private final Quotations quotations;
    private final RepairRequestDirectory requests;
    private final ApplicationEventPublisher events;

    public RejectQuotation(Quotations quotations, RepairRequestDirectory requests, ApplicationEventPublisher events) {
        this.quotations = quotations;
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public QuotationSummary execute(CurrentActor actor, UUID quotationId) {
        QuotationAccess.requireActive(actor);

        var target = quotations.findById(quotationId)
                .orElseThrow(QuotationNotFoundException::new);

        var request = requests.lockForDecision(target.requestId());
        request.requireDecidedBy(actor.internalUserId());

        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request is no longer open");
        }

        var quotation = quotations.findByIdForUpdate(quotationId)
                .orElseThrow(QuotationNotFoundException::new);

        if (!quotation.isSubmitted()) {
            throw new QuotationConflictException("QUOTATION_NOT_SUBMITTED",
                    "The quotation is no longer open for decision");
        }

        var rejected = quotation.reject(Instant.now());
        quotations.update(rejected);
        events.publishEvent(NotificationRequested.of(rejected.fixerUserId(), NotificationType.QUOTATION_REJECTED,
                "Rechazaron tu cotización",
                "El cliente no aceptó tu oferta para \"" + request.title() + "\".",
                "/quotations/me", rejected.id(), "QUOTATION"));

        return QuotationSummary.of(rejected);
    }
}
