package com.fixup.messaging.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.messaging.domain.ChatMessages;
import com.fixup.requests.api.RepairRequestDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-24: the two participants read the same thread; a PLATFORM_ADMIN may read it for moderation. */
@Service
public class ListChatMessages {
    private final ChatMessages messages;
    private final RepairRequestDirectory requests;

    ListChatMessages(ChatMessages messages, RepairRequestDirectory requests) {
        this.messages = messages;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageSummary> execute(CurrentActor actor, UUID requestId) {
        ChatAccess.requireActive(actor);
        var request = requests.require(requestId);
        if (!actor.hasRole(Role.PLATFORM_ADMIN)) {
            ChatAccess.requireParticipant(actor, request);
        }
        return messages.findByRequest(requestId).stream().map(ChatMessageSummary::of).toList();
    }
}
