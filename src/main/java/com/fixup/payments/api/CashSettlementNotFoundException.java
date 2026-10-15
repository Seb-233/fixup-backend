package com.fixup.payments.api;

public class CashSettlementNotFoundException extends RuntimeException {
    public CashSettlementNotFoundException() {
        super("The cash settlement does not exist");
    }
}
