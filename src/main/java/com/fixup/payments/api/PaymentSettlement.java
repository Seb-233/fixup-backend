package com.fixup.payments.api;

/**
 * Synchronous financial settlement for a completed job. A failed settlement rolls the
 * job and request states back within the same transaction.
 */
public interface PaymentSettlement {

    void settle(PaymentSettlementCommand command);
}
