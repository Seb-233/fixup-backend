package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.payments.api.PaymentInitiation;
import com.fixup.payments.api.PaymentInitiationCommand;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestSnapshot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-18: el propietario elige una cotización. La aceptada, el rechazo de las demás y el cierre
 * de la solicitud ocurren en la misma transacción: nunca queda una solicitud con dos ganadores.
 */
@Service
public class AcceptQuotation {
    private final Quotations quotations;
    private final RepairRequestDirectory requests;
    private final PaymentInitiation paymentInitiation;
    private final ApplicationEventPublisher events;
    private final com.fixup.payments.api.PaymentContextRegistration contexts;
    private final com.fixup.properties.api.PropertyDirectory properties;

    AcceptQuotation(Quotations quotations, RepairRequestDirectory requests,
            PaymentInitiation paymentInitiation, ApplicationEventPublisher events,
            com.fixup.payments.api.PaymentContextRegistration contexts,
            com.fixup.properties.api.PropertyDirectory properties) {
        this.quotations = quotations;
        this.requests = requests;
        this.paymentInitiation = paymentInitiation;
        this.events = events;
        this.contexts = contexts;
        this.properties = properties;
    }

    @Transactional
    public AcceptedQuotation execute(CurrentActor actor, UUID quotationId) {
        QuotationAccess.requireActive(actor);

        // 1. Read quotation without locking to find the target requestId.
        var target = quotations.findById(quotationId)
                .orElseThrow(QuotationNotFoundException::new);
        var requestId = target.requestId();

        // 2. Lock repair_requests FIRST by requestId: prevents deadlock between concurrent acceptances.
        var request = requests.lockForDecision(requestId);
        if (request.paymentMethod() != PaymentMethod.TRANSFER) {
            throw new QuotationConflictException("DIGITAL_PAYMENT_REQUIRED", "An old CASH request cannot open a new CASH job");
        }

        // 3. Verify that the request is still open.
        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request was already assigned");
        }

        // 4. Verify that the caller decides on the property: its owner or its manager (FR-UC-11).
        var property = properties.lockForFinancialDecision(request.propertyId());
        if (!property.ownerUserId().equals(request.ownerUserId())
                || !(actor.hasRole(com.fixup.identityaccess.api.Role.OWNER)
                        && property.ownerUserId().equals(actor.internalUserId()))
                    && !(actor.hasRole(com.fixup.identityaccess.api.Role.REAL_ESTATE_MANAGER)
                        && property.isManagedBy(actor.internalUserId()))) {
            throw new com.fixup.requests.api.RepairRequestAccessDeniedException();
        }

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
        var rejected = allOffers.stream()
                .filter(offer -> !offer.id().equals(quotationId))
                .filter(Quotation::isSubmitted)
                .toList();
        rejected.forEach(offer -> quotations.update(offer.reject(now)));

        // 9. Assign the request.
        requests.assign(requestId, accepted.fixerUserId());

        contexts.capture(new com.fixup.payments.api.PaymentContext(accepted.id(), requestId, property.id(),
                request.ownerUserId(), accepted.fixerUserId(), property.managerUserId(),
                request.specialty().name(), request.title(), now, "ACCEPTANCE"));

        UUID paymentId = null;
        if (request.paymentMethod() == PaymentMethod.TRANSFER) {
            paymentId = paymentInitiation.initiate(new PaymentInitiationCommand(accepted.id(),
                    accepted.requestId(), request.ownerUserId(), accepted.fixerUserId(),
                    accepted.amount(), request.paymentMethod().name(), now)).paymentId();
        }

        // 10. Publish event.
        events.publishEvent(new QuotationAccepted(accepted.id(), accepted.requestId(),
                accepted.fixerUserId(), request.ownerUserId(), accepted.amount(), now,
                request.paymentMethod()));

        notifyDecision(request, accepted, rejected);
        return new AcceptedQuotation(accepted.id(), accepted.requestId(), request.paymentMethod(), paymentId);
    }

    private void notifyDecision(RepairRequestSnapshot request, Quotation accepted, java.util.List<Quotation> rejected) {
        var title = request.title() == null ? "la solicitud" : "\"" + request.title() + "\"";
        boolean opensImmediately = request.paymentMethod() == PaymentMethod.CASH;
        events.publishEvent(NotificationRequested.of(accepted.fixerUserId(), NotificationType.QUOTATION_ACCEPTED,
                "Aceptaron tu cotización",
                "Tu oferta para " + title + " fue aceptada. "
                        + (opensImmediately ? "El trabajo ya aparece en Mis trabajos."
                                : "El trabajo se abrirá cuando se apruebe el pago."),
                opensImmediately ? "/jobs/me" : "/quotations/me", accepted.id(), "QUOTATION"));
        for (Quotation offer : rejected) {
            events.publishEvent(NotificationRequested.of(offer.fixerUserId(), NotificationType.QUOTATION_REJECTED,
                    "Eligieron otra oferta", "El cliente eligió otra cotización para " + title + ".",
                    "/quotations/me", offer.id(), "QUOTATION"));
        }
        if (request.requestedByUserId() != null && !request.isDecidedBy(request.requestedByUserId())) {
            events.publishEvent(NotificationRequested.of(request.requestedByUserId(), NotificationType.JOB_ASSIGNED,
                    "Ya hay técnico para tu reporte",
                    "Se asignó un técnico a " + title + ". Puedes escribirle desde la solicitud.",
                    "/requests/" + request.requestId(), request.requestId(), "REQUEST"));
        }
    }
}
