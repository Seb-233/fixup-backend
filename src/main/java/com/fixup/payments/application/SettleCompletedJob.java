package com.fixup.payments.application;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentSettlement;
import com.fixup.payments.api.PaymentSettlementCommand;
import com.fixup.payments.domain.CashSettlement;
import com.fixup.payments.domain.CashSettlements;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.OwnerDebt;
import com.fixup.payments.domain.OwnerDebts;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SettleCompletedJob implements PaymentSettlement {
    private final FixerEarnings earnings;
    private final CashSettlements settlements;
    private final OwnerDebts debts;

    SettleCompletedJob(FixerEarnings earnings, CashSettlements settlements, OwnerDebts debts) {
        this.earnings = earnings;
        this.settlements = settlements;
        this.debts = debts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(PaymentSettlementCommand command) {
        if (command.paymentMethod().equals("TRANSFER")) {
            if (command.cashReceivedAmount() != null) {
                throw new PaymentConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                        "Transfer jobs do not accept cash settlement");
            }
            var earning = earnings.findByQuotationForUpdate(command.quotationId())
                    .orElseThrow(() -> new PaymentConflictException("EARNING_NOT_HELD",
                            "The transfer earning is missing"));
            if (earning.grossAmount() != command.agreedAmount()
                    || !earning.fixerUserId().equals(command.fixerUserId())) {
                throw new PaymentConflictException("EARNING_NOT_HELD",
                        "The transfer earning does not match the job");
            }
            if (earning.status() == EarningStatus.HELD) {
                earnings.update(earning.release(command.completedAt()));
            }
            return;
        }
        if (!command.paymentMethod().equals("CASH") || command.cashReceivedAmount() == null) {
            throw new PaymentConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                    "Cash jobs require a received amount");
        }
        var existing = settlements.findByJob(command.jobId());
        if (existing.isPresent()) {
            var settled = existing.orElseThrow();
            if (settled.agreedAmount() != command.agreedAmount()
                    || settled.receivedAmount() != command.cashReceivedAmount()
                    || !settled.quotationId().equals(command.quotationId())) {
                throw new PaymentConflictException("CASH_SETTLEMENT_CONFLICT",
                        "The job already has a different cash settlement");
            }
            return;
        }
        var settlement = CashSettlement.create(UUID.randomUUID(), command.jobId(),
                command.quotationId(), command.requestId(), command.ownerUserId(),
                command.fixerUserId(), command.agreedAmount(), command.cashReceivedAmount(),
                command.completedAt());
        settlements.create(settlement);
        if (settlement.outstandingAmount() > 0) {
            debts.create(OwnerDebt.outstanding(UUID.randomUUID(), command.ownerUserId(),
                    command.fixerUserId(), command.requestId(), command.quotationId(),
                    settlement.outstandingAmount(), command.completedAt()));
        }
    }
}
