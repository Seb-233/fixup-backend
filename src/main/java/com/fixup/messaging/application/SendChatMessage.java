package com.fixup.messaging.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.messaging.api.ChatConflictException;
import com.fixup.notifications.api.NotificationStatus;
import com.fixup.notifications.api.ChatNotifications;
import com.fixup.messaging.domain.ChatMessage;
import com.fixup.messaging.domain.ChatMessages;
import com.fixup.notifications.api.PushNotificationGateway;
import com.fixup.notifications.api.PushNotificationGateway.PushNotification;
import com.fixup.notifications.api.PushNotificationPayload;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-24: the chat is writable while the request is active and has an assigned fixer, for the
 * owner, reporter, manager and fixer. Push to each other participant is best-effort: it
 * is attempted before the message is persisted so the final, real outcome is what gets stored, but
 * a failing or unavailable provider never stops the message itself from sending.
 *
 * <p>The gateway returns an explicit {@link NotificationStatus} -- no exception-as-control-flow:
 * {@code SENT} for real delivery, {@code SKIPPED} when no provider is configured, {@code FAILED}
 * when a configured provider reports an error.
 */
@Service
public class SendChatMessage {
    private static final Logger LOG = LoggerFactory.getLogger(SendChatMessage.class);
    private static final int PREVIEW_LENGTH = 80;

    private final ChatMessages messages;
    private final RepairRequestDirectory requests;
    private final PushNotificationGateway pushGateway;
    private final ChatNotifications chatNotifications;

    SendChatMessage(ChatMessages messages, RepairRequestDirectory requests,
            PushNotificationGateway pushGateway, ChatNotifications chatNotifications) {
        this.messages = messages;
        this.requests = requests;
        this.pushGateway = pushGateway;
        this.chatNotifications = chatNotifications;
    }

    @Transactional
    public ChatMessageSummary execute(CurrentActor actor, UUID requestId, NewChatMessage draft) {
        ChatAccess.requireActive(actor);
        var request = requests.require(requestId);
        // SLA alerts keep the assignment active; closed requests are read-only.
        if (!request.isAssigned()) {
            throw new ChatConflictException("REQUEST_NOT_ASSIGNED",
                    "The chat requires an assigned fixer");
        }
        ChatAccess.requireParticipant(actor, request);

        var recipients = request.participants().stream()
                .filter(participant -> !participant.equals(actor.internalUserId()))
                .toList();
        var messageId = UUID.randomUUID();
        var sentAt = Instant.now();
        var notificationStatus = NotificationStatus.SKIPPED;
        for (var recipientId : recipients) {
            var outcome = attemptNotification(recipientId, requestId, messageId, actor.internalUserId(), draft.body());
            // One stored outcome: any failure wins; otherwise any delivery wins over skipped.
            if (outcome == NotificationStatus.FAILED
                    || (outcome == NotificationStatus.SENT && notificationStatus != NotificationStatus.FAILED)) {
                notificationStatus = outcome;
            }
        }

        var message = ChatMessage.sent(messageId, requestId, actor.internalUserId(), draft.body(),
                sentAt, notificationStatus);
        messages.save(message);
        // This API persists the internal notice in this transaction. A NotificationRequested
        // event here would duplicate both that notice and the push already attempted above.
        for (var recipientId : recipients) {
            chatNotifications.messageReceived(message.id(), requestId, actor.internalUserId(),
                    recipientId, preview(draft.body()), message.sentAt());
        }
        return ChatMessageSummary.of(message);
    }

    private NotificationStatus attemptNotification(UUID recipientId, UUID requestId, UUID messageId,
            UUID senderId, String body) {
        try {
            // Gateway returns the outcome explicitly; SKIPPED means no provider is configured (Noop),
            // FAILED means a real provider was attempted and could not deliver.
            var payload = new PushNotificationPayload("/requests/" + requestId + "/chat", messageId, "CHAT_MESSAGE",
                    Map.of("requestId", requestId.toString(), "senderId", senderId.toString()));
            NotificationStatus status = pushGateway.send(new PushNotification(recipientId, "Nuevo mensaje",
                    preview(body), payload));
            if (status == NotificationStatus.FAILED) {
                LOG.warn("Push notification failed for recipient {}", recipientId);
            }
            return status;
        } catch (RuntimeException e) {
            // Unexpected infrastructure failure outside the provider contract.
            LOG.warn("Push notification threw unexpectedly for recipient {}: {}", recipientId, e.getMessage());
            return NotificationStatus.FAILED;
        }
    }

    private String preview(String body) {
        String trimmed = body.trim();
        return trimmed.length() <= PREVIEW_LENGTH ? trimmed : trimmed.substring(0, PREVIEW_LENGTH - 3) + "...";
    }
}
