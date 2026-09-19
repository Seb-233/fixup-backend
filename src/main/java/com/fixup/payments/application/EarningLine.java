package com.fixup.payments.application;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.domain.FixerEarning;
import java.time.Instant;
import java.util.UUID;

/** Una línea del historial de servicios cobrados, con su comisión a la vista. */
public record EarningLine(UUID id, UUID quotationId, long grossAmount, long commissionAmount,
        long netAmount, int commissionRateBasisPoints, EarningStatus status, Instant createdAt,
        Instant releasedAt) {

    static EarningLine of(FixerEarning earning) {
        return new EarningLine(earning.id(), earning.quotationId(), earning.grossAmount(),
                earning.commissionAmount(), earning.netAmount(), earning.commissionRateBasisPoints(),
                earning.status(), earning.createdAt(), earning.releasedAt());
    }
}
