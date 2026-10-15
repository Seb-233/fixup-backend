package com.fixup.requests.web;

import com.fixup.fixers.api.Specialty;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaState;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.application.ListSlaBoardRequests;
import com.fixup.requests.application.Page;
import com.fixup.requests.application.SlaBoardPageable;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/administration/sla-board", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Missing PLATFORM_ADMIN role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Resource not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class SlaBoardController {

    private static final Duration WARNING_THRESHOLD = Duration.ofMinutes((long) (48L * 60L * 0.20));

    private final RepairRequests requests;
    private final ListSlaBoardRequests listSlaBoardRequests;
    private final CurrentActorProvider currentActorProvider;

    SlaBoardController(RepairRequests requests, CurrentActorProvider currentActorProvider,
            ListSlaBoardRequests listSlaBoardRequests) {
        this.requests = requests;
        this.listSlaBoardRequests = listSlaBoardRequests;
        this.currentActorProvider = currentActorProvider;
    }

    private void requirePlatformAdmin() {
        var actor = currentActorProvider.currentActor();
        if (actor == null || !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new AccessDeniedException("PLATFORM_ADMIN role required");
        }
    }

    @GetMapping("/requests")
    @Operation(summary = "List SLA board requests",
            description = "Paginated list of requests in SLA_WARNING or SLA_BREACHED status, "
                    + "ordered by SLA deadline ascending.")
    @ApiResponse(responseCode = "200", description = "Paged SLA board requests")
    Page<SlaBoardItemResponse> listRequests(SlaBoardPageable pageable) {
        requirePlatformAdmin();
        return listSlaBoardRequests.execute(pageable, SlaBoardItemResponse::of);
    }

    @PostMapping("/requests/{id}/reassign")
    @Operation(summary = "Reassign an at-risk request to a different fixer",
            description = "Updates the assignedFixerUserId of the request. Resets the SLA status "
                    + "if it was in SLA_WARNING (not SLA_BREACHED) so the new fixer gets a fair shot.")
    @ApiResponse(responseCode = "200", description = "Request reassigned successfully")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    SlaBoardItemResponse reassign(@PathVariable UUID id, @Valid @RequestBody ReassignFixerRequest body) {
        requirePlatformAdmin();
        RepairRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new RepairRequestNotFoundException(id));
        Instant now = Instant.now();
        RepairRequest updated = request.withAssignedFixerUserId(body.fixerUserId(), now);
        if (updated.status() == RepairRequestStatus.SLA_WARNING) {
            updated = updated.withStatus(RepairRequestStatus.ASSIGNED, now);
        }
        requests.update(updated);
        return SlaBoardItemResponse.of(updated);
    }

    @PostMapping("/requests/{id}/acknowledge")
    @Operation(summary = "Acknowledge an SLA escalation",
            description = "Updates lastEscalationNotifiedAt so the next scheduler run does not "
                    + "re-trigger the same notification immediately.")
    @ApiResponse(responseCode = "200", description = "Escalation acknowledged")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    ResponseEntity<Void> acknowledge(@PathVariable UUID id) {
        requirePlatformAdmin();
        RepairRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new RepairRequestNotFoundException(id));
        Instant now = Instant.now();
        RepairRequest updated = request.withLastEscalationNotifiedAt(now);
        requests.update(updated);
        return ResponseEntity.ok().build();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record ReassignFixerRequest(@NotNull UUID fixerUserId) {
    }

    @Schema(requiredProperties = {"requestId", "status", "urgencyLevel", "slaDeadline", "slaState"})
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record SlaBoardItemResponse(
            UUID requestId,
            UUID propertyId,
            String propertyCity,
            Specialty specialty,
            String title,
            RepairRequestStatus status,
            UrgencyLevel urgencyLevel,
            Instant slaDeadline,
            SlaState slaState,
            Long remainingMinutes,
            UUID assignedFixerUserId,
            Instant lastEscalationNotifiedAt,
            Instant createdAt) {

        static SlaBoardItemResponse of(RepairRequest request) {
            SlaState slaState = computeSlaState(request);
            Long remainingMinutes = request.slaDeadline() == null ? null
                    : Math.max(0, Duration.between(Instant.now(), request.slaDeadline()).toMinutes());
            return new SlaBoardItemResponse(
                    request.id(),
                    request.propertyId(),
                    request.propertyCity(),
                    request.specialty(),
                    request.title(),
                    request.status(),
                    request.urgencyLevel(),
                    request.slaDeadline(),
                    slaState,
                    remainingMinutes,
                    request.assignedFixerUserId(),
                    request.lastEscalationNotifiedAt(),
                    request.createdAt());
        }

        private static SlaState computeSlaState(RepairRequest request) {
            if (request.slaDeadline() == null) {
                return SlaState.NONE;
            }
            if (request.status() == RepairRequestStatus.SLA_BREACHED) {
                return SlaState.BREACHED;
            }
            if (request.status() == RepairRequestStatus.SLA_WARNING) {
                return SlaState.WARNING;
            }
            Instant now = Instant.now();
            if (request.slaDeadline().isBefore(now)) {
                return SlaState.BREACHED;
            }
            Duration remaining = Duration.between(now, request.slaDeadline());
            if (remaining.compareTo(WARNING_THRESHOLD) <= 0) {
                return SlaState.WARNING;
            }
            return SlaState.ON_TRACK;
        }
    }
}
