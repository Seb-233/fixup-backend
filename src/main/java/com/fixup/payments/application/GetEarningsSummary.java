package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-20: saldo disponible, retenido, comisiones e historial del técnico actual. */
@Service
public class GetEarningsSummary {
    private final FixerEarnings earnings;

    GetEarningsSummary(FixerEarnings earnings) {
        this.earnings = earnings;
    }

    @Transactional(readOnly = true)
    public EarningsSummary execute(CurrentActor actor) {
        PaymentAccess.requireActiveFixer(actor);
        var own = earnings.findByFixer(actor.internalUserId());

        return new EarningsSummary(
                sumNet(own, EarningStatus.AVAILABLE),
                sumNet(own, EarningStatus.HELD),
                sumNet(own, EarningStatus.PAID_OUT),
                own.stream().mapToLong(FixerEarning::commissionAmount).sum(),
                own.stream().mapToLong(FixerEarning::grossAmount).sum(),
                own.stream().map(EarningLine::of).toList());
    }

    private long sumNet(List<FixerEarning> own, EarningStatus status) {
        return own.stream().filter(earning -> earning.status() == status)
                .mapToLong(FixerEarning::netAmount).sum();
    }
}
