package com.fixup.media.infrastructure;

import com.fixup.media.api.PortfolioVisibility;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPortfolioPieces implements PortfolioPieces {
    private final PortfolioPieceJpaRepository repository;

    JpaPortfolioPieces(PortfolioPieceJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(PortfolioPiece piece) {
        var entity = repository.findById(piece.id()).orElseGet(() -> PortfolioPieceEntity.from(piece));
        entity.apply(piece);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<PortfolioPiece> findById(UUID pieceId) {
        return repository.findById(pieceId).map(PortfolioPieceEntity::toDomain);
    }

    @Override
    public List<PortfolioPiece> findAllOfFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdOrderByPositionAsc(fixerUserId).stream()
                .map(PortfolioPieceEntity::toDomain).toList();
    }

    @Override
    public List<PortfolioPiece> findPublicOfFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdAndVisibilityOrderByPositionAsc(
                fixerUserId, PortfolioVisibility.PUBLIC).stream()
                .map(PortfolioPieceEntity::toDomain).toList();
    }
}
