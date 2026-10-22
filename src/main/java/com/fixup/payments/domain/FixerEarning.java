package com.fixup.payments.domain;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: lo que un trabajo le deja al técnico. Con TRANSFER nace retenido cuando se aprueba el
 * pago (FR-UC-05) y se libera cuando el trabajo se cierra; el saldo de un trabajo en efectivo que el
 * propietario paga después nace ya liberado. Aceptar una cotización no crea ingreso. Sale de la
 * cuenta cuando se transfiere.
 *
 * El monto bruto, la comisión y el neto se guardan los tres: recalcularlos después significaría
 * que un cambio de tarifa reescribe la historia.
 */
public record FixerEarning(UUID id, UUID quotationId, UUID fixerUserId, long grossAmount,
        long commissionAmount, long netAmount, int commissionRateBasisPoints, EarningStatus status,
        Instant createdAt, Instant releasedAt, Instant paidOutAt, Instant updatedAt,
        Instant fundedAt, Instant jobCompletedAt) {

    public FixerEarning(UUID id, UUID quotationId, UUID fixerUserId, long grossAmount,
            long commissionAmount, long netAmount, int commissionRateBasisPoints, EarningStatus status,
            Instant createdAt, Instant releasedAt, Instant paidOutAt, Instant updatedAt) {
        this(id, quotationId, fixerUserId, grossAmount, commissionAmount, netAmount, commissionRateBasisPoints,
                status, createdAt, releasedAt, paidOutAt, updatedAt, null, null);
    }

    public FixerEarning {
        if (commissionAmount + netAmount != grossAmount) {
            throw new PaymentConflictException("INCONSISTENT_EARNING",
                    "The commission and the net amount must add up to the gross amount");
        }
    }

    /** El pago entró pero el dinero todavía no es del técnico: el trabajo no se ha cerrado. */
    public static FixerEarning held(UUID id, UUID quotationId, UUID fixerUserId, long grossAmount,
            Instant now) {
        var commission = CommissionPolicy.commissionFor(grossAmount);
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commission,
                grossAmount - commission, CommissionPolicy.RATE_BASIS_POINTS, EarningStatus.HELD,
                now, null, null, now, now, null);
    }

    public FixerEarning release(Instant now) {
        if (status != EarningStatus.HELD) {
            throw new PaymentConflictException("EARNING_NOT_HELD",
                    "Only an earning still in escrow can be released");
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount,
                netAmount, commissionRateBasisPoints, EarningStatus.AVAILABLE, createdAt, now, null, now,
                fundedAt, jobCompletedAt == null ? now : jobCompletedAt);
    }

    public FixerEarning payOut(Instant now) {
        if (status != EarningStatus.AVAILABLE) {
            throw new PaymentConflictException("EARNING_NOT_AVAILABLE",
                    "Only an available earning can be transferred");
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount,
                netAmount, commissionRateBasisPoints, EarningStatus.PAID_OUT, createdAt, releasedAt,
                now, now, fundedAt, jobCompletedAt);
    }

    public boolean isAvailable() {
        return status == EarningStatus.AVAILABLE;
    }
}
