package com.fixup.payments.api;

import java.util.UUID;

/** Narrow read contract used to enforce the future owner-debt creation rule. */
public interface OwnerDebtDirectory {

    boolean hasOutstandingDebt(UUID ownerUserId);
}
