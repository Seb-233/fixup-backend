package com.fixup.audit.web;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.application.ListStatusChanges;
import com.fixup.audit.application.StatusChangeView;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/administration/audit", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Missing PLATFORM_ADMIN role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class StatusAuditController {
    private final ListStatusChanges listStatusChanges;
    private final CurrentActorProvider currentActorProvider;

    StatusAuditController(ListStatusChanges listStatusChanges, CurrentActorProvider currentActorProvider) {
        this.listStatusChanges = listStatusChanges;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping("/status-changes")
    @Operation(summary = "List audited status changes",
            description = "Administrative trace of state transitions across requests, contracts, payments and jobs.")
    List<StatusChangeResponse> list(
            @RequestParam Optional<AuditedResourceType> resourceType,
            @RequestParam Optional<UUID> resourceId,
            @RequestParam(defaultValue = "100") int limit) {
        return listStatusChanges.execute(currentActorProvider.currentActor(), resourceType, resourceId, limit).stream()
                .map(StatusChangeResponse::of)
                .toList();
    }

    record StatusChangeResponse(
            UUID id,
            AuditedResourceType resourceType,
            UUID resourceId,
            String previousStatus,
            String newStatus,
            UUID changedByUserId,
            String changedBySubject,
            String changedByDisplayName,
            java.time.Instant changedAt) {

        static StatusChangeResponse of(StatusChangeView view) {
            return new StatusChangeResponse(view.id(), view.resourceType(), view.resourceId(),
                    view.previousStatus(), view.newStatus(), view.changedByUserId(),
                    view.changedBySubject(), view.changedByDisplayName(), view.changedAt());
        }
    }
}
