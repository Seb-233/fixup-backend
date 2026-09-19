package com.fixup.quotations.infrastructure;

import com.fixup.quotations.api.QuotationStatus;
import com.fixup.quotations.domain.Quotation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quotations")
class QuotationEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;
    @Column(name = "estimated_days", nullable = false, updatable = false)
    private int estimatedDays;
    @Column(name = "message", length = 1000, updatable = false)
    private String message;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private QuotationStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuotationEntity() {
    }

    static QuotationEntity from(Quotation quotation) {
        var entity = new QuotationEntity();
        entity.id = quotation.id();
        entity.requestId = quotation.requestId();
        entity.fixerUserId = quotation.fixerUserId();
        entity.amount = quotation.amount();
        entity.estimatedDays = quotation.estimatedDays();
        entity.message = quotation.message();
        entity.createdAt = quotation.createdAt();
        entity.apply(quotation);
        return entity;
    }

    /** A quotation is never re-priced: only its decision and the moment of it can change. */
    void apply(Quotation quotation) {
        status = quotation.status();
        updatedAt = quotation.updatedAt();
    }

    Quotation toDomain() {
        return new Quotation(id, requestId, fixerUserId, amount, estimatedDays, message, status,
                createdAt, updatedAt);
    }
}
