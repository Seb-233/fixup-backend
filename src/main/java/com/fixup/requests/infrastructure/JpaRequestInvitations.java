package com.fixup.requests.infrastructure;

import com.fixup.requests.domain.RequestInvitation;
import com.fixup.requests.domain.RequestInvitations;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaRequestInvitations implements RequestInvitations {
    private final RequestInvitationJpaRepository repository;

    JpaRequestInvitations(RequestInvitationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean exists(UUID requestId, UUID fixerUserId) {
        return repository.existsByRequestIdAndFixerUserId(requestId, fixerUserId);
    }

    @Override
    public void create(RequestInvitation invitation) {
        repository.saveAndFlush(RequestInvitationEntity.from(invitation));
    }

    @Override
    public List<RequestInvitation> findByRequest(UUID requestId) {
        return repository.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(RequestInvitationEntity::toDomain).toList();
    }

    @Override
    public Set<UUID> invitedRequestIds(UUID fixerUserId) {
        return Set.copyOf(repository.findRequestIdsByFixer(fixerUserId));
    }
}
