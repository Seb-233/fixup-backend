package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebts;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListOwnDebts {
    private final OwnerDebts debts;

    ListOwnDebts(OwnerDebts debts) {
        this.debts = debts;
    }

    @Transactional(readOnly = true)
    public List<OwnerDebtDetail> execute(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.OWNER)) {
            throw new PaymentAccessDeniedException();
        }
        return debts.findByOwner(actor.internalUserId()).stream()
                .filter(debt -> debt.status() == DebtStatus.OUTSTANDING)
                .map(OwnerDebtDetail::from).toList();
    }
}
