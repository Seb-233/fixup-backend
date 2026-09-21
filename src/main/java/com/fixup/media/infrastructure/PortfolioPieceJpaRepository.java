package com.fixup.media.infrastructure;

import com.fixup.media.api.PortfolioVisibility;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PortfolioPieceJpaRepository extends JpaRepository<PortfolioPieceEntity, UUID> {
    List<PortfolioPieceEntity> findByFixerUserIdOrderByPositionAsc(UUID fixerUserId);

    List<PortfolioPieceEntity> findByFixerUserIdAndVisibilityOrderByPositionAsc(
            UUID fixerUserId, PortfolioVisibility visibility);

    /**
     * Serializes the publications of one portfolio: the rows of that fixer are held until the
     * transaction ends, so only one publication at a time counts pieces and picks a position.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select piece from PortfolioPieceEntity piece where piece.fixerUserId = :fixerUserId "
            + "order by piece.position asc")
    List<PortfolioPieceEntity> lockByFixerUserIdOrderByPositionAsc(@Param("fixerUserId") UUID fixerUserId);
}
