package com.fixup.payments;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.SimulatedPaymentAttempt;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepairPaymentTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final UUID ID = UUID.randomUUID();
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();

    @Test
    void createsTransferAndCashPaymentsWithCanonicalMethods() {
        assertThat(payment(RepairPaymentMethod.TRANSFER).paymentMethod()).isEqualTo(RepairPaymentMethod.TRANSFER);
        assertThat(payment(RepairPaymentMethod.CASH).paymentMethod()).isEqualTo(RepairPaymentMethod.CASH);
        assertThat(RepairPaymentMethod.fromCanonical("TRANSFER")).isEqualTo(RepairPaymentMethod.TRANSFER);
        assertThat(RepairPaymentMethod.fromCanonical("CASH")).isEqualTo(RepairPaymentMethod.CASH);
    }

    @Test
    void rejectsInvalidAmountsIdsAndMethodNames() {
        assertThatThrownBy(() -> RepairPayment.pending(ID, QUOTATION, REQUEST, OWNER, FIXER, 0,
                RepairPaymentMethod.TRANSFER, PaymentPurpose.SERVICE, NOW)).isInstanceOf(PaymentConflictException.class);
        assertThatThrownBy(() -> RepairPayment.pending(ID, QUOTATION, REQUEST, OWNER, FIXER, -1,
                RepairPaymentMethod.TRANSFER, PaymentPurpose.SERVICE, NOW)).isInstanceOf(PaymentConflictException.class);
        assertThatThrownBy(() -> RepairPayment.pending(ID, null, REQUEST, OWNER, FIXER, 1,
                RepairPaymentMethod.TRANSFER, PaymentPurpose.SERVICE, NOW)).isInstanceOf(NullPointerException.class);
        for (String invalid : new String[] {null, "", "transfer", "cash", "CARD", "PSE", "SANDBOX_CARD"}) {
            assertThatThrownBy(() -> RepairPaymentMethod.fromCanonical(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void approvedIsIdempotentButCannotBeDegraded() {
        var approved = payment(RepairPaymentMethod.TRANSFER).approve(NOW.plusSeconds(1));
        assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.completedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(approved.approve(NOW.plusSeconds(2))).isSameAs(approved);
        assertThatThrownBy(() -> approved.decline(NOW.plusSeconds(2))).isInstanceOf(PaymentConflictException.class);
        assertThatThrownBy(() -> approved.fail(NOW.plusSeconds(2))).isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void declinedOrErroredPaymentCanBeRetriedToApproval() {
        assertThat(payment(RepairPaymentMethod.CASH).decline(NOW.plusSeconds(1)).approve(NOW.plusSeconds(2)).status())
                .isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment(RepairPaymentMethod.CASH).fail(NOW.plusSeconds(1)).approve(NOW.plusSeconds(2)).status())
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void pendingOutcomeCanRestoreARetryablePaymentToPending() {
        var pendingAgain = payment(RepairPaymentMethod.TRANSFER)
                .decline(NOW.plusSeconds(1)).keepPending(NOW.plusSeconds(2));

        assertThat(pendingAgain.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pendingAgain.completedAt()).isNull();
        assertThat(pendingAgain.updatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void attemptsRetainEveryAuditedValue() {
        var attempt = new SimulatedPaymentAttempt(UUID.randomUUID(), ID, PaymentStatus.DECLINED,
                PaymentStatus.DECLINED, OWNER, NOW);
        assertThat(attempt.paymentId()).isEqualTo(ID);
        assertThat(attempt.requestedOutcome()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(attempt.resultingStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(attempt.attemptedByUserId()).isEqualTo(OWNER);
        assertThat(attempt.attemptedAt()).isEqualTo(NOW);
    }

    private RepairPayment payment(RepairPaymentMethod method) {
        return RepairPayment.pending(ID, QUOTATION, REQUEST, OWNER, FIXER, 100_000, method,
                PaymentPurpose.SERVICE, NOW);
    }
}
