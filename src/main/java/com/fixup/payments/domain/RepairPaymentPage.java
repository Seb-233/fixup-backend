package com.fixup.payments.domain;

import java.util.List;

/** A bounded, deterministically ordered page of visible canonical payments. */
public record RepairPaymentPage(List<RepairPayment> items, int page, int size, boolean hasMore) {
    public RepairPaymentPage {
        items = List.copyOf(items);
    }
}
