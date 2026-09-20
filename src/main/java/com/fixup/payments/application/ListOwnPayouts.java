package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.domain.Payouts;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-20: historial de solicitudes de transferencia del técnico actual. */
@Service
public class ListOwnPayouts {
    private final Payouts payouts;

    ListOwnPayouts(Payouts payouts) {
        this.payouts = payouts;
    }

    @Transactional(readOnly = true)
    public List<PayoutSummary> execute(CurrentActor actor) {
        PaymentAccess.requireActiveFixer(actor);
        return payouts.findByFixer(actor.internalUserId()).stream().map(PayoutSummary::of).toList();
    }
}
