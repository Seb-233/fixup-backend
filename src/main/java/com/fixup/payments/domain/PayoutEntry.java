package com.fixup.payments.domain;
import java.util.UUID;
/** Immutable net amount actually included in a simulated payout. */
public record PayoutEntry(UUID earningId, long netAmount) { }
