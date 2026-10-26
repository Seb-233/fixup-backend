package com.fixup.requests.infrastructure;

import com.fixup.requests.domain.RequestInvitation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "request_invitations")
class RequestInvitationEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "invited_by_user_id", nullable = false, updatable = false)
    private UUID invitedByUserId;
    @Column(name = "note", length = 500, updatable = false)
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RequestInvitationEntity() {
    }

    static RequestInvitationEntity from(RequestInvitation invitation) {
        var entity = new RequestInvitationEntity();
        entity.id = invitation.id();
        entity.requestId = invitation.requestId();
        entity.fixerUserId = invitation.fixerUserId();
        entity.invitedByUserId = invitation.invitedByUserId();
        entity.note = invitation.note();
        entity.createdAt = invitation.createdAt();
        return entity;
    }

    RequestInvitation toDomain() {
        return new RequestInvitation(id, requestId, fixerUserId, invitedByUserId, note, createdAt);
    }
}
