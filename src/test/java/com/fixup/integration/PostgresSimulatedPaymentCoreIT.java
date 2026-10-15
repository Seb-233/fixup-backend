package com.fixup.integration;

import com.fixup.payments.api.OwnerDebtDirectory;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebt;
import com.fixup.payments.domain.OwnerDebts;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import com.fixup.payments.domain.SimulatedPaymentAttempt;
import com.fixup.payments.domain.SimulatedPaymentAttempts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
class PostgresSimulatedPaymentCoreIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RepairPayments payments;
    @Autowired OwnerDebts debts;
    @Autowired OwnerDebtDirectory debtDirectory;
    @Autowired SimulatedPaymentAttempts attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner cleaner;
    private final Instant now = Instant.parse("2026-10-14T12:00:00Z");

    @BeforeEach void clean() { cleaner.clean(); }

    @Test
    void persistsReloadsAndAuditsRetryablePaymentAttempts() {
        var payment = payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        payments.create(payment);
        attempts.create(new SimulatedPaymentAttempt(UUID.randomUUID(), payment.id(), PaymentStatus.DECLINED,
                PaymentStatus.DECLINED, payment.ownerUserId(), now));
        attempts.create(new SimulatedPaymentAttempt(UUID.randomUUID(), payment.id(), PaymentStatus.APPROVED,
                PaymentStatus.APPROVED, payment.ownerUserId(), now.plusSeconds(1)));

        assertThat(payments.findById(payment.id()).orElseThrow().paymentMethod()).isEqualTo(RepairPaymentMethod.TRANSFER);
        assertThat(payments.findById(payment.id()).orElseThrow().purpose()).isEqualTo(PaymentPurpose.SERVICE);
        assertThat(attempts.findByPayment(payment.id())).extracting(SimulatedPaymentAttempt::resultingStatus)
                .containsExactly(PaymentStatus.DECLINED, PaymentStatus.APPROVED);
    }

    @Test
    void databaseEnforcesPaymentAndDebtConstraintsAndOutstandingLookup() {
        UUID quotation = UUID.randomUUID(); UUID owner = UUID.randomUUID(); UUID fixer = UUID.randomUUID();
        payments.create(payment(quotation, UUID.randomUUID(), owner, fixer, UUID.randomUUID()));
        assertThatThrownBy(() -> payments.create(payment(quotation, UUID.randomUUID(), owner, fixer, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO repair_payments (id, quotation_id, request_id, owner_user_id, fixer_user_id, amount, payment_method, purpose, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, 'CARD', 'SERVICE', 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), owner, fixer))
                .isInstanceOf(DataIntegrityViolationException.class);

        var debt = OwnerDebt.outstanding(UUID.randomUUID(), owner, fixer, UUID.randomUUID(), UUID.randomUUID(), 20_000, now);
        debts.create(debt);
        assertThat(debtDirectory.hasOutstandingDebt(owner)).isTrue();
        assertThatThrownBy(() -> debts.create(OwnerDebt.outstanding(UUID.randomUUID(), owner, fixer, UUID.randomUUID(), debt.quotationId(), 30_000, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        debts.update(debt.settle(now.plusSeconds(1)));
        assertThat(debtDirectory.hasOutstandingDebt(owner)).isFalse();
        assertThat(debts.findById(debt.id()).orElseThrow().status()).isEqualTo(DebtStatus.PAID);
        assertThatThrownBy(() -> debts.create(OwnerDebt.outstanding(UUID.randomUUID(), owner, fixer,
                UUID.randomUUID(), debt.quotationId(), 40_000, now.plusSeconds(2))))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID outstandingBalancePaymentId = UUID.randomUUID();
        payments.create(RepairPayment.pending(outstandingBalancePaymentId, quotation, UUID.randomUUID(), owner,
                fixer, 10_000, RepairPaymentMethod.TRANSFER, PaymentPurpose.OUTSTANDING_BALANCE, now));
        assertThat(payments.findById(outstandingBalancePaymentId)).isPresent();
    }

    private RepairPayment payment(UUID quotation, UUID request, UUID owner, UUID fixer, UUID id) {
        return RepairPayment.pending(id, quotation, request, owner, fixer, 100_000, RepairPaymentMethod.TRANSFER,
                PaymentPurpose.SERVICE, now);
    }
}
