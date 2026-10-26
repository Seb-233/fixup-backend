package com.fixup.requests.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RequestInvitationJpaRepository extends JpaRepository<RequestInvitationEntity, UUID> {
    boolean existsByRequestIdAndFixerUserId(UUID requestId, UUID fixerUserId);

    List<RequestInvitationEntity> findByRequestIdOrderByCreatedAtAsc(UUID requestId);

    @Query("select invitation.requestId from RequestInvitationEntity invitation where invitation.fixerUserId = :fixerUserId")
    List<UUID> findRequestIdsByFixer(@Param("fixerUserId") UUID fixerUserId);
}
