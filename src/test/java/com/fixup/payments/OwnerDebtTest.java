package com.fixup.payments;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebt;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerDebtTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    void outstandingDebtCanBeSettledIdempotently() {
        var debt = debt();
        assertThat(debt.status()).isEqualTo(DebtStatus.OUTSTANDING);
        var paid = debt.settle(NOW.plusSeconds(1));
        assertThat(paid.status()).isEqualTo(DebtStatus.PAID);
        assertThat(paid.settledAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(paid.settle(NOW.plusSeconds(2))).isSameAs(paid);
    }

    @Test
    void debtAmountMustBePositive() {
        assertThatThrownBy(() -> OwnerDebt.outstanding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 0, NOW)).isInstanceOf(PaymentConflictException.class);
    }

    private OwnerDebt debt() {
        return OwnerDebt.outstanding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 50_000, NOW);
    }
}
