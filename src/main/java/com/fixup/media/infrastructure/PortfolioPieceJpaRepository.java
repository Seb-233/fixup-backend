package com.fixup.media.infrastructure;

import com.fixup.media.api.PortfolioVisibility;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PortfolioPieceJpaRepository extends JpaRepository<PortfolioPieceEntity, UUID> {
    List<PortfolioPieceEntity> findByFixerUserIdOrderByPositionAsc(UUID fixerUserId);

    List<PortfolioPieceEntity> findByFixerUserIdAndVisibilityOrderByPositionAsc(
            UUID fixerUserId, PortfolioVisibility visibility);
}
