package com.fixup.media.infrastructure;

import com.fixup.media.api.PortfolioRuleException;
import com.fixup.media.api.PortfolioVisibility;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPieces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
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
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException conflict) {
            // The only constraint a concurrent writer can break here is the unique position of the
            // portfolio. It is a conflict between two clients, never an internal failure, so it
            // leaves this adapter as a portfolio rule and the API answers 409.
            throw new PortfolioRuleException("PORTFOLIO_POSITION_TAKEN",
                    "Another publication took that portfolio position, try again");
        }
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
    public List<PortfolioPiece> findAllOfFixerForUpdate(UUID fixerUserId) {
        try {
            return repository.lockByFixerUserIdOrderByPositionAsc(fixerUserId).stream()
                    .map(PortfolioPieceEntity::toDomain).toList();
        } catch (PessimisticLockingFailureException busy) {
            // Losing the race for the lock is a conflict between two publications of the same
            // portfolio, so it is reported as such instead of surfacing as an internal error.
            throw new PortfolioRuleException("PORTFOLIO_BUSY",
                    "Another publication is in progress for this portfolio, try again");
        }
    }

    @Override
    public List<PortfolioPiece> findPublicOfFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdAndVisibilityOrderByPositionAsc(
                fixerUserId, PortfolioVisibility.PUBLIC).stream()
                .map(PortfolioPieceEntity::toDomain).toList();
    }

    @Override
    public void delete(UUID pieceId) {
        repository.deleteById(pieceId);
        repository.flush();
    }
}
