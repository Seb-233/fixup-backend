package com.fixup.messaging.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.messaging.api.ChatConflictException;
import com.fixup.messaging.api.NotificationStatus;
import com.fixup.messaging.domain.ChatMessage;
import com.fixup.messaging.domain.ChatMessages;
import com.fixup.notifications.api.PushNotificationGateway;
import com.fixup.notifications.api.PushNotificationGateway.PushNotification;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-24: the chat opens the moment a fixer is assigned to the request, and only for the two
 * people that assignment names. The push notification to the other participant is best-effort: it
 * is attempted before the message is persisted so the final, real outcome is what gets stored, but
 * a failing or unavailable provider never stops the message itself from sending.
 */
@Service
public class SendChatMessage {
    private static final Logger LOG = LoggerFactory.getLogger(SendChatMessage.class);
    private static final int PREVIEW_LENGTH = 80;

    private final ChatMessages messages;
    private final RepairRequestDirectory requests;
    private final PushNotificationGateway pushGateway;

    SendChatMessage(ChatMessages messages, RepairRequestDirectory requests, PushNotificationGateway pushGateway) {
        this.messages = messages;
        this.requests = requests;
        this.pushGateway = pushGateway;
    }

    @Transactional
    public ChatMessageSummary execute(CurrentActor actor, UUID requestId, NewChatMessage draft) {
        ChatAccess.requireActive(actor);
        var request = requests.require(requestId);
        // KNOWN LIMITATION / EXTENSION POINT: isAssigned() matches the ASSIGNED status exactly. It
        // is complete today because ASSIGNED is the only non-OPEN status, but if a future terminal
        // status is added (e.g. COMPLETED), sending stops the moment a request leaves ASSIGNED --
        // mirroring chat_messages_insert's WITH CHECK in V10__chat_messages_rls.sql, which must be
        // revisited together with this check. Reading history is unaffected either way: it is never
        // gated on status. Whether a completed job's chat should stay writable for wrap-up messages
        // is a product decision nobody has made yet -- don't assume either answer when that status
        // is introduced.
        if (!request.isAssigned()) {
            throw new ChatConflictException("REQUEST_NOT_ASSIGNED",
                    "The chat opens only once the repair request has an assigned fixer");
        }
        ChatAccess.requireParticipant(actor, request);

        var recipientId = actor.internalUserId().equals(request.ownerUserId())
                ? request.assignedFixerUserId() : request.ownerUserId();
        var notificationStatus = attemptNotification(recipientId, draft.body());

        var message = ChatMessage.sent(UUID.randomUUID(), requestId, actor.internalUserId(), draft.body(),
                Instant.now(), notificationStatus);
        messages.save(message);
        return ChatMessageSummary.of(message);
    }

    private NotificationStatus attemptNotification(UUID recipientId, String body) {
        try {
            pushGateway.send(new PushNotification(recipientId, "New message", preview(body)));
            return NotificationStatus.SENT;
        } catch (RuntimeException e) {
            LOG.warn("Push notification failed for recipient {}: {}", recipientId, e.getMessage());
            return NotificationStatus.FAILED;
        }
    }

    private String preview(String body) {
        String trimmed = body.trim();
        return trimmed.length() <= PREVIEW_LENGTH ? trimmed : trimmed.substring(0, PREVIEW_LENGTH - 3) + "...";
    }
}
