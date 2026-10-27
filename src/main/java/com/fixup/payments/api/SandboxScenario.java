package com.fixup.payments.api;

/** Server-controlled sandbox scenario. Never supplied in the public payment request. */
public enum SandboxScenario { APPROVE, DECLINE_FUNDS, DECLINE_BANK, PENDING, ERROR }
