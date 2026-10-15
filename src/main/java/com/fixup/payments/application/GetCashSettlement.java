package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.CashSettlementNotFoundException;
import com.fixup.payments.domain.CashSettlements;
import com.fixup.payments.domain.OwnerDebts;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetCashSettlement {
    private final CashSettlements settlements;
    private final OwnerDebts debts;

    GetCashSettlement(CashSettlements settlements, OwnerDebts debts) {
        this.settlements = settlements;
        this.debts = debts;
    }

    @Transactional(readOnly = true)
    public CashSettlementDetail execute(CurrentActor actor, UUID jobId) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new PaymentAccessDeniedException();
        }
        var settlement = settlements.findByJob(jobId)
                .orElseThrow(CashSettlementNotFoundException::new);
        if (!actor.hasRole(Role.PLATFORM_ADMIN)
                && !settlement.ownerUserId().equals(actor.internalUserId())
                && !settlement.fixerUserId().equals(actor.internalUserId())) {
            throw new PaymentAccessDeniedException();
        }
        UUID debtId = debts.findByQuotation(settlement.quotationId())
                .map(debt -> debt.id()).orElse(null);
        return CashSettlementDetail.from(settlement, debtId);
    }
}
