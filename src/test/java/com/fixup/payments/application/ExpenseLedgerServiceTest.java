package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentAccessDeniedException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpenseLedgerServiceTest {
    private final com.fixup.payments.domain.PaidExpenses rows = org.mockito.Mockito.mock(com.fixup.payments.domain.PaidExpenses.class);
    private final com.fixup.properties.api.PropertyDirectory properties = org.mockito.Mockito.mock(com.fixup.properties.api.PropertyDirectory.class);
    private final ExpenseLedgerService ledger = new ExpenseLedgerService(rows, properties);

    @Test
    void authorizedReadersCanReadAnActuallyEmptyPeriod() {
        for (var role : Set.of(Role.OWNER, Role.REAL_ESTATE_MANAGER, Role.PLATFORM_ADMIN)) {
            org.assertj.core.api.Assertions.assertThat(ledger.approvedExpenses(actor(role), Instant.EPOCH, Instant.now())).isEmpty();
        }
    }

    @Test
    void ledgerStillRequiresAnAuthorizedRole() {
        for (var role : Set.of(Role.FIXER, Role.TENANT)) {
            assertThatThrownBy(() -> ledger.approvedExpenses(actor(role), Instant.EPOCH, Instant.now()))
                    .isInstanceOf(PaymentAccessDeniedException.class);
        }
    }

    @Test
    void anOwnedPaidExpenseWithoutContextFailsExplicitly() {
        var owner = actor(Role.OWNER);
        var expense = new com.fixup.payments.api.ExpenseLedger.Expense(UUID.randomUUID(), null,
                UUID.randomUUID(), null, null, 1000, UUID.randomUUID(), Instant.now());
        org.mockito.Mockito.when(rows.between(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new com.fixup.payments.domain.PaidExpenses.Row(owner.internalUserId(), expense)));
        assertThatThrownBy(() -> ledger.approvedExpenses(owner, Instant.EPOCH, Instant.now()))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("EXPENSE_CONTEXT_INCOMPLETE");
    }

    private CurrentActor actor(Role role) {
        return new CurrentActor(UUID.randomUUID(), "auth0|ledger", Set.of(role), UserStatus.ACTIVE);
    }
}
