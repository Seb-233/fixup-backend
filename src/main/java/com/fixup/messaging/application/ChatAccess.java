package com.fixup.messaging.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.messaging.api.ChatAccessDeniedException;
import com.fixup.requests.api.RepairRequestSnapshot;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class ChatAccess {
    private ChatAccess() {
    }

    static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ChatAccessDeniedException();
        }
    }

    /**
     * The only two people who ever belong in this chat: the request's owner and its assigned fixer.
     * A PLATFORM_ADMIN can still read the request snapshot (and, under RLS, the messages themselves,
     * for moderation) but is deliberately never treated as a participant here -- an admin moderates
     * a conversation, it does not speak inside it.
     */
    static void requireParticipant(CurrentActor actor, RepairRequestSnapshot request) {
        var userId = actor.internalUserId();
        if (!userId.equals(request.ownerUserId()) && !userId.equals(request.assignedFixerUserId())) {
            throw new ChatAccessDeniedException();
        }
    }
}
