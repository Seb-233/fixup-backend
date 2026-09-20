package com.fixup.messaging.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.messaging.api.NotificationStatus;
import com.fixup.messaging.application.ChatMessageSummary;
import com.fixup.messaging.application.ListChatMessages;
import com.fixup.messaging.application.NewChatMessage;
import com.fixup.messaging.application.SendChatMessage;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-UC-24: private chat for an assigned repair request. Nested under /requests because a chat
 * message is a sub-resource of the request it belongs to, not a first-class resource of its own.
 * Controllers stay thin and never touch JPA.
 */
@RestController
@RequestMapping(value = "/requests/{requestId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Blank body, body too long, or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or not a participant of this chat",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The repair request does not exist or is not visible to the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The request has no assigned fixer yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class ChatController {
    private final CurrentActorProvider actors;
    private final SendChatMessage sendMessage;
    private final ListChatMessages listMessages;

    ChatController(CurrentActorProvider actors, SendChatMessage sendMessage, ListChatMessages listMessages) {
        this.actors = actors;
        this.sendMessage = sendMessage;
        this.listMessages = listMessages;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Send a chat message",
            description = "Requires an active account that is either the owner of the request or its "
                    + "assigned fixer, and the request must already have an assigned fixer.")
    @ApiResponse(responseCode = "201", description = "The message was recorded")
    @ResponseStatus(HttpStatus.CREATED)
    MessageResponse send(@PathVariable UUID requestId, @Valid @RequestBody SendMessageRequest body) {
        var summary = sendMessage.execute(actors.currentActor(), requestId, new NewChatMessage(body.body()));
        return MessageResponse.of(summary);
    }

    @GetMapping
    @Operation(summary = "Read the chat thread for a repair request",
            description = "Requires an active account that is either the owner of the request, its "
                    + "assigned fixer, or an active PLATFORM_ADMIN. Oldest message first.")
    @ApiResponse(responseCode = "200", description = "Messages in the order they were sent")
    List<MessageResponse> list(@PathVariable UUID requestId) {
        return listMessages.execute(actors.currentActor(), requestId).stream()
                .map(MessageResponse::of).toList();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record SendMessageRequest(@NotBlank @Size(max = 2000) String body) {
    }

    @Schema(requiredProperties = {"id", "requestId", "senderUserId", "body", "sentAt", "notificationStatus"})
    record MessageResponse(UUID id, UUID requestId, UUID senderUserId, String body, Instant sentAt,
            NotificationStatus notificationStatus) {

        static MessageResponse of(ChatMessageSummary summary) {
            return new MessageResponse(summary.id(), summary.requestId(), summary.senderUserId(),
                    summary.body(), summary.sentAt(), summary.notificationStatus());
        }
    }
}
