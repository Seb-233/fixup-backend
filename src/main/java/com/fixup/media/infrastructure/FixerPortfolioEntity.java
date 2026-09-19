package com.fixup.media.infrastructure;

import com.fixup.media.domain.FixerPortfolio;
import com.fixup.media.domain.PortfolioStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fixer_portfolios")
class FixerPortfolioEntity {
    @Id
    @Column(name = "fixer_user_id", nullable = false)
    private UUID fixerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PortfolioStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FixerPortfolioEntity() {
    }

    static FixerPortfolioEntity from(FixerPortfolio portfolio) {
        var entity = new FixerPortfolioEntity();
        entity.fixerUserId = portfolio.fixerUserId();
        entity.apply(portfolio);
        return entity;
    }

    void apply(FixerPortfolio portfolio) {
        this.status = portfolio.status();
        this.publishedAt = portfolio.publishedAt();
        this.updatedAt = portfolio.updatedAt();
    }

    FixerPortfolio toDomain() {
        return new FixerPortfolio(fixerUserId, status, publishedAt, updatedAt);
    }
}
