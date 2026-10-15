package com.fixup.payments.api;

import java.util.UUID;

/** Narrow read contract used by request and remodeling creation. */
public interface OwnerDebtDirectory {

    boolean hasOutstandingDebt(UUID ownerUserId);
}
