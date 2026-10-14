package com.fixup.payments.domain;

import com.fixup.payments.api.ChargeStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentMethod;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-05 y FR-UC-22: el cobro al propietario por una cotización aceptada. Nace PENDING con una
 * referencia única; la pasarela de pruebas solo lo aprueba si quien paga es el propietario (o su
 * administrador), la referencia coincide y el valor es exactamente el de la cotización. Un cobro
 * rechazado se puede reintentar; uno aprobado ya no cambia.
 */
public record OwnerCharge(UUID id, UUID quotationId, UUID requestId, UUID propertyId, UUID ownerUserId,
        UUID managerUserId, UUID fixerUserId, String specialty, String concept, long amount, String reference,
        ChargeStatus status, PaymentMethod method, String gatewayTransactionId, String failureReason, int attempts,
        UUID paidByUserId, Instant createdAt, Instant updatedAt, Instant approvedAt) {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static OwnerCharge pending(UUID quotationId, UUID requestId, UUID propertyId, UUID ownerUserId,
            UUID managerUserId, UUID fixerUserId, String specialty, String concept, long amount, Instant now) {
        if (amount <= 0) {
            throw new PaymentConflictException("INVALID_AMOUNT", "A charge must be for a positive amount");
        }
        return new OwnerCharge(UUID.randomUUID(), quotationId, requestId, propertyId, ownerUserId, managerUserId,
                fixerUserId, specialty, concept, amount, newReference(), ChargeStatus.PENDING, null, null, null, 0,
                null, now, now, null);
    }

    public boolean isPayableBy(UUID userId) {
        return ownerUserId.equals(userId) || (managerUserId != null && managerUserId.equals(userId));
    }

    /** Valida usuario, referencia y valor antes de llamar a la pasarela: nada de eso se negocia. */
    public void requireAttempt(UUID payer, String givenReference, long givenAmount) {
        if (!isPayableBy(payer)) {
            throw new PaymentAccessDeniedException();
        }
        if (status == ChargeStatus.APPROVED) {
            throw new PaymentConflictException("CHARGE_ALREADY_PAID", "This charge is already paid");
        }
        if (givenReference == null || !reference.equals(givenReference.trim())) {
            throw new PaymentConflictException("REFERENCE_MISMATCH", "The payment reference does not match the charge");
        }
        if (givenAmount != amount) {
            throw new PaymentConflictException("AMOUNT_MISMATCH", "The amount must be exactly the accepted quotation");
        }
    }

    public OwnerCharge approve(UUID payer, PaymentMethod usedMethod, String transactionId, Instant now) {
        return new OwnerCharge(id, quotationId, requestId, propertyId, ownerUserId, managerUserId, fixerUserId,
                specialty, concept, amount, reference, ChargeStatus.APPROVED, usedMethod, transactionId, null,
                attempts + 1, payer, createdAt, now, now);
    }

    public OwnerCharge decline(UUID payer, PaymentMethod usedMethod, String transactionId, String reason, Instant now) {
        return new OwnerCharge(id, quotationId, requestId, propertyId, ownerUserId, managerUserId, fixerUserId,
                specialty, concept, amount, reference, ChargeStatus.DECLINED, usedMethod, transactionId, reason,
                attempts + 1, payer, createdAt, now, null);
    }

    static String newReference() {
        var builder = new StringBuilder("FXR-");
        for (int index = 0; index < 8; index++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
