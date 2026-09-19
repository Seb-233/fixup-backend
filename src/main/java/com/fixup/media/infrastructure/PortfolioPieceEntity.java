package com.fixup.media.infrastructure;

import com.fixup.media.api.PortfolioVisibility;
import com.fixup.media.domain.PortfolioPiece;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Owned by media. This entity is never shared with another module nor exposed as a contract. */
@Entity
@Table(name = "portfolio_pieces")
class PortfolioPieceEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "fixer_user_id", nullable = false)
    private UUID fixerUserId;

    @Column(name = "media_asset_id", nullable = false, unique = true)
    private UUID mediaAssetId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "display_position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 32)
    private PortfolioVisibility visibility;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PortfolioPieceEntity() {
    }

    static PortfolioPieceEntity from(PortfolioPiece piece) {
        var entity = new PortfolioPieceEntity();
        entity.id = piece.id();
        entity.fixerUserId = piece.fixerUserId();
        entity.apply(piece);
        return entity;
    }

    void apply(PortfolioPiece piece) {
        this.mediaAssetId = piece.mediaId();
        this.title = piece.title();
        this.description = piece.description();
        this.position = piece.position();
        this.visibility = piece.visibility();
        this.createdAt = piece.createdAt();
        this.updatedAt = piece.updatedAt();
    }

    PortfolioPiece toDomain() {
        return new PortfolioPiece(id, fixerUserId, mediaAssetId, title, description, position,
                visibility, createdAt, updatedAt);
    }
}
