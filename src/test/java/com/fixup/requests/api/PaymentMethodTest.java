package com.fixup.requests.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodTest {

    @Test
    void onlyTransferAndCashAreSupported() {
        assertThat(PaymentMethod.values()).containsExactly(PaymentMethod.TRANSFER, PaymentMethod.CASH);
    }
}
