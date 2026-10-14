package com.fixup.payments.domain;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: lo que un trabajo le deja al técnico. Nace retenido cuando el propietario acepta la
 * cotización, se libera cuando el propietario pagó (FR-UC-05) y el trabajo se cerró, y sale de la
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

    /** El dinero queda comprometido pero todavía no es del técnico: el trabajo no se ha hecho. */
    public static FixerEarning held(UUID id, UUID quotationId, UUID fixerUserId, long grossAmount,
            Instant now) {
        var commission = CommissionPolicy.commissionFor(grossAmount);
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commission,
                grossAmount - commission, CommissionPolicy.RATE_BASIS_POINTS, EarningStatus.HELD,
                now, null, null, now, null, null);
    }

    /** FR-UC-05: el propietario pagó; el dinero queda en garantía hasta que el trabajo termine. */
    public FixerEarning fund(Instant now) {
        if (fundedAt != null) {
            return this;
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount, netAmount,
                commissionRateBasisPoints, status, createdAt, releasedAt, paidOutAt, now, now, jobCompletedAt);
    }

    /** FR-UC-19: el técnico cerró el trabajo; si el pago ya entró, el dinero se puede liberar. */
    public FixerEarning markJobCompleted(Instant now) {
        if (jobCompletedAt != null) {
            return this;
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount, netAmount,
                commissionRateBasisPoints, status, createdAt, releasedAt, paidOutAt, now, fundedAt, now);
    }

    /** El escrow se libera cuando ocurrieron los dos hechos: el pago y el cierre del trabajo. */
    public boolean isReleasable() {
        return status == EarningStatus.HELD && fundedAt != null && jobCompletedAt != null;
    }

    public FixerEarning release(Instant now) {
        if (status != EarningStatus.HELD) {
            throw new PaymentConflictException("EARNING_NOT_HELD",
                    "Only an earning still in escrow can be released");
        }
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount,
                netAmount, commissionRateBasisPoints, EarningStatus.AVAILABLE, createdAt, now, null, now,
                fundedAt, jobCompletedAt);
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
