package com.fixup.payments.domain;

import com.fixup.payments.api.ExpenseLedger.Expense;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PaidExpenses {
    List<Row> between(Instant from, Instant to);
    record Row(UUID ownerUserId, Expense expense) { }
}
