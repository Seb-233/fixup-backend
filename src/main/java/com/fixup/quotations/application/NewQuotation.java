package com.fixup.quotations.application;

import java.util.UUID;

/** Amount in whole Colombian pesos and estimate in whole days, as the fixer typed them. */
public record NewQuotation(UUID requestId, long amount, int estimatedDays, String message) {
}
