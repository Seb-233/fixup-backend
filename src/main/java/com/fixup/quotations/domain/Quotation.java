package com.fixup.quotations.domain;

import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-18: la oferta del Fixer sobre una solicitud. El monto se guarda en pesos colombianos
 * enteros: no hay centavos que redondear ni coma decimal que interpretar.
 */
public record Quotation(UUID id, UUID requestId, UUID fixerUserId, long amount, int estimatedDays,
        String message, QuotationStatus status, Instant createdAt, Instant updatedAt) {

    public static final long MAX_AMOUNT = 9_007_199_254_740_991L;
    public static final int MAX_ESTIMATED_DAYS = 365;

    public Quotation {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new QuotationConflictException("INVALID_AMOUNT",
                    "The quoted amount must be between 1 and " + MAX_AMOUNT);
        }
        if (estimatedDays < 1 || estimatedDays > MAX_ESTIMATED_DAYS) {
            throw new QuotationConflictException("INVALID_ESTIMATE",
                    "The estimate must be between 1 and " + MAX_ESTIMATED_DAYS + " days");
        }
    }

    public static Quotation submitted(UUID id, UUID requestId, UUID fixerUserId, long amount,
            int estimatedDays, String message, Instant now) {
        return new Quotation(id, requestId, fixerUserId, amount, estimatedDays, message,
                QuotationStatus.SUBMITTED, now, now);
    }

    public Quotation accept(Instant now) {
        requireSubmitted();
        return new Quotation(id, requestId, fixerUserId, amount, estimatedDays, message,
                QuotationStatus.ACCEPTED, createdAt, now);
    }

    /** Losing offers are closed by the same acceptance, never by the fixers themselves. */
    public Quotation reject(Instant now) {
        requireSubmitted();
        return new Quotation(id, requestId, fixerUserId, amount, estimatedDays, message,
                QuotationStatus.REJECTED, createdAt, now);
    }

    public boolean isSubmitted() {
        return status == QuotationStatus.SUBMITTED;
    }

    private void requireSubmitted() {
        if (!isSubmitted()) {
            throw new QuotationConflictException("QUOTATION_ALREADY_DECIDED",
                    "The quotation is " + status + " and no longer admits a decision");
        }
    }
}
