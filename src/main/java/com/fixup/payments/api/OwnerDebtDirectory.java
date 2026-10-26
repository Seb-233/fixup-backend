package com.fixup.payments.api;

import java.util.UUID;

/** Narrow read contract used by request and remodeling creation. */
public interface OwnerDebtDirectory {

    /** RLS-filtered owner read; not an authorization check for third-party reporters. */
    boolean hasOutstandingDebt(UUID ownerUserId);

    /**
     * Check the canonical owner's debt for a property the current database actor may report.
     * The implementation must independently authorize the relationship and match the expected
     * owner. It returns only existence, never financial details. Missing actor, unauthorized
     * relationship, owner mismatch or an unavailable check must fail, never return false.
     * Requires the caller's actor-bound transaction; no actor substitution is permitted.
     */
    boolean hasOutstandingDebtForProperty(UUID propertyId, UUID expectedOwnerUserId);
}
