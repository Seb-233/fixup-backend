package com.fixup.payments.domain;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: lo que un trabajo le deja al técnico. Nace retenido cuando el propietario acepta la
 * cotización, se libera cuando el trabajo se cierra y sale de la cuenta cuando se transfiere.
 *
 * El monto bruto, la comisión y el neto se guardan los tres: recalcularlos después significaría
 * que un cambio de tarifa reescribe la historia.
 */
public record FixerEarning(UUID id, UUID quotationId, UUID fixerUserId, long grossAmount,
        long commissionAmount, long netAmount, int commissionRateBasisPoints, EarningStatus status,
        Instant createdAt, Instant releasedAt, Instant paidOutAt, Instant updatedAt) {

    public FixerEarning {
        if (commissionAmount + netAmount != grossAmount) {
            throw new PaymentConflictException("INCONSISTENT_EARNING",
                    "The commission and the net amount must add up to the gross amount");
        }
    }

    /** El dinero queda comprometido pero todavía no es del técnico: el trabajo no se ha hecho. */
    public static FixerEarning held(UUID id, UUID quotationId, UUID fixerUserId, long grossAmount,
            Instant now) {
        var commission = CommissionPolicy.commissionFor(grossAmount);
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commission,
                grossAmount - commission, CommissionPolicy.RATE_BASIS_POINTS, EarningStatus.HELD,
                now, null, null, now);
    }

    public FixerEarning release(Instant now) {
        if (status != EarningStatus.HELD) {
            throw new PaymentConflictException("EARNING_NOT_HELD",
                    "Only an earning still in escrow can be released");
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount,
                netAmount, commissionRateBasisPoints, EarningStatus.AVAILABLE, createdAt, now, null, now);
    }

    public FixerEarning payOut(Instant now) {
        if (status != EarningStatus.AVAILABLE) {
            throw new PaymentConflictException("EARNING_NOT_AVAILABLE",
                    "Only an available earning can be transferred");
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount,
                netAmount, commissionRateBasisPoints, EarningStatus.PAID_OUT, createdAt, releasedAt,
                now, now);
    }

    public boolean isAvailable() {
        return status == EarningStatus.AVAILABLE;
    }
}
