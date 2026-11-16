package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentMethod;
import com.fixup.payments.api.PaymentNotFoundException;
import com.fixup.payments.application.PayWithWompi.WompiTransactionResult;
import com.fixup.payments.domain.HostedCheckoutGateway.ProviderTransaction;
import com.fixup.payments.domain.PaymentGateway;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayments;
import com.fixup.payments.domain.SimulatedPaymentAttempts;
import com.fixup.payments.domain.WompiCheckout;
import com.fixup.payments.domain.WompiCheckouts;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INT-01: registro del resultado de una transacción de Wompi en el pago. Es el único camino de
 * escritura de Wompi; el retorno del navegador y el webhook llegan aquí con un actor y ya con la
 * transacción releída en Wompi (la llamada HTTP queda fuera de la transacción de base de datos).
 */
@Service
public class WompiSettlement {
    private static final Logger log = LoggerFactory.getLogger(WompiSettlement.class);
    private static final String CURRENCY = "COP";

    private final WompiCheckouts checkouts;
    private final RepairPayments payments;
    private final SimulatedPaymentAttempts attempts;
    private final PaymentAuthorization authorization;
    private final SimulateRepairPayment recorder;

    WompiSettlement(WompiCheckouts checkouts, RepairPayments payments, SimulatedPaymentAttempts attempts,
            PaymentAuthorization authorization, SimulateRepairPayment recorder) {
        this.checkouts = checkouts;
        this.payments = payments;
        this.attempts = attempts;
        this.authorization = authorization;
        this.recorder = recorder;
    }

    /**
     * Quién abrió el checkout de una referencia. Lo usa solo el webhook, antes de tener actor, para
     * saber con qué identidad aplicar el evento; no expone nada del pago.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> openerOf(String reference) {
        return checkouts.findByReference(reference).map(WompiCheckout::createdByUserId);
    }

    /**
     * Aplica una transacción ya leída de Wompi. Siempre con un actor (el pagador que vuelve del
     * checkout o, en el webhook, quien lo abrió): requirePayer y el RLS de su sesión aplican igual.
     * Bloquea el pago antes de mirar si la transacción ya se registró, así que un retorno y un webhook
     * simultáneos se serializan y solo uno crea el intento (además, gateway_transaction_id es único).
     */
    @Transactional
    public WompiTransactionResult settle(ProviderTransaction transaction, UUID expectedPaymentId, CurrentActor actor) {
        Objects.requireNonNull(actor, "Wompi results are always applied as the paying actor");
        var checkout = checkouts.findByReference(transaction.reference())
                .orElseThrow(() -> new PaymentConflictException("WOMPI_REFERENCE_UNKNOWN",
                        "The Wompi transaction does not belong to a FixUp checkout"));
        if (expectedPaymentId != null && !checkout.paymentId().equals(expectedPaymentId)) {
            throw new PaymentConflictException("WOMPI_PAYMENT_MISMATCH",
                    "The Wompi transaction belongs to another payment");
        }
        if (transaction.amountInCents() != checkout.amountInCents() || !CURRENCY.equals(transaction.currency())) {
            throw new PaymentConflictException("WOMPI_AMOUNT_MISMATCH",
                    "The Wompi transaction amount differs from the checkout");
        }
        var payment = payments.findByIdForUpdate(checkout.paymentId()).orElseThrow(PaymentNotFoundException::new);
        authorization.requirePayer(actor, payment);
        if (Math.multiplyExact(payment.amount(), 100L) != checkout.amountInCents()) {
            throw new PaymentConflictException("WOMPI_AMOUNT_MISMATCH", "The payment amount changed after checkout");
        }

        UUID operationKey = UUID.nameUUIDFromBytes(("wompi:" + transaction.id()).getBytes(StandardCharsets.UTF_8));
        var previous = attempts.findByOperation(payment.id(), operationKey);
        if (previous.isPresent()) {
            return WompiTransactionResult.of(transaction, payment.status(), PaymentAttemptResult.of(previous.get()));
        }

        PaymentStatus outcome = switch (transaction.status()) {
            case "APPROVED" -> PaymentStatus.APPROVED;
            case "DECLINED", "VOIDED" -> PaymentStatus.DECLINED;
            case "ERROR" -> PaymentStatus.ERROR;
            default -> PaymentStatus.PENDING;
        };
        if (outcome == PaymentStatus.PENDING) {
            return WompiTransactionResult.of(transaction, payment.status(), null);
        }
        if (payment.status() == PaymentStatus.APPROVED) {
            if (outcome == PaymentStatus.APPROVED) {
                // El pago ya estaba cubierto por otra transacción: no se crea un segundo ingreso.
                log.error("Doble cobro en Wompi: la transacción {} aprobó el pago {} que ya estaba aprobado; "
                        + "hay que reembolsarla desde el panel de Wompi", transaction.id(), payment.id());
            }
            return WompiTransactionResult.of(transaction, payment.status(), null);
        }

        UUID attemptedBy = actor.internalUserId();
        String scenario = ("WOMPI_" + (transaction.paymentMethodType() == null ? "CHECKOUT"
                : transaction.paymentMethodType())).toUpperCase(Locale.ROOT);
        String failure = switch (outcome) {
            case DECLINED -> "VOIDED".equals(transaction.status()) ? "Wompi anuló la transacción"
                    : "Wompi rechazó la transacción" + reason(transaction);
            case ERROR -> "Wompi reportó un error en la transacción" + reason(transaction);
            default -> null;
        };
        var processed = recorder.record(attemptedBy, payment,
                new PaymentAttemptCommand(payment.reference(), payment.amount(), PaymentMethod.WOMPI, operationKey),
                scenario.length() > 32 ? scenario.substring(0, 32) : scenario,
                new PaymentGateway.Result(outcome, transaction.id(), failure));
        return WompiTransactionResult.of(transaction, processed.detail().status(), processed.result());
    }

    private static String reason(ProviderTransaction transaction) {
        String message = transaction.statusMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        String trimmed = message.strip();
        return ": " + (trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed);
    }

}
