package com.fixup.payments.api;

/**
 * Financial settlement requested by jobs after a job is completed.
 *
 * <p>The future implementation will release transfer earnings or record cash received without
 * importing the jobs module.
 */
public interface PaymentSettlement {

    void settle(PaymentSettlementCommand command);
}
