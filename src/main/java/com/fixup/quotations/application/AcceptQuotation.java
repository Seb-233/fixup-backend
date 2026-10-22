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
 * FR-UC-18: el propietario o su administrador elige una cotización. La aceptada, el rechazo de las
 * demás y la asignación de la solicitud ocurren en la misma transacción: nunca queda una solicitud
 * con dos ganadores. Con transferencia nace el pago pendiente (FR-UC-05) y el trabajo se abre al
 * aprobarse; con efectivo el trabajo se abre en esta misma transacción.
 */
@Service
public class AcceptQuotation {
    private final Quotations quotations;
    private final RepairRequestDirectory requests;
    private final PaymentInitiation paymentInitiation;
    private final ApplicationEventPublisher events;

    AcceptQuotation(Quotations quotations, RepairRequestDirectory requests,
            PaymentInitiation paymentInitiation, ApplicationEventPublisher events) {
        this.quotations = quotations;
        this.requests = requests;
        this.paymentInitiation = paymentInitiation;
        this.events = events;
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

        // 3. Verify that the request is still open.
        if (!request.isOpen()) {
            throw new QuotationConflictException("REQUEST_NOT_OPEN",
                    "The repair request was already assigned");
        }

        // 4. Verify that the caller decides on the property: its owner or its manager (FR-UC-11).
        request.requireDecidedBy(actor.internalUserId());

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

        UUID paymentId = null;
        if (request.paymentMethod() == PaymentMethod.TRANSFER) {
            paymentId = paymentInitiation.initiate(new PaymentInitiationCommand(accepted.id(),
                    accepted.requestId(), request.ownerUserId(), accepted.fixerUserId(),
                    accepted.amount(), request.paymentMethod().name(), now, request.managerUserId())).paymentId();
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
        var cash = request.paymentMethod() == PaymentMethod.CASH;
        events.publishEvent(NotificationRequested.of(accepted.fixerUserId(), NotificationType.QUOTATION_ACCEPTED,
                "Aceptaron tu cotización",
                cash ? "Tu oferta para " + title + " fue aceptada con pago en efectivo. El trabajo ya aparece "
                        + "en Mis trabajos; al cerrarlo registras lo que recibiste."
                        : "Tu oferta para " + title + " fue aceptada. El trabajo se abre en cuanto el cliente "
                        + "complete el pago por transferencia.",
                cash ? "/jobs/me" : "/quotations/me", accepted.id(), "QUOTATION"));
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
