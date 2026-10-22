package com.fixup.requests.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RequestInvitations {
    boolean exists(UUID requestId, UUID fixerUserId);

    void create(RequestInvitation invitation);

    List<RequestInvitation> findByRequest(UUID requestId);

    Set<UUID> invitedRequestIds(UUID fixerUserId);
}
