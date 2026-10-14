package com.fixup.requests.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaState;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.application.ManageMaintenanceRequests;
import com.fixup.requests.application.RequestPeople;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-11: tablero del administrador inmobiliario sobre las solicitudes de los inmuebles que gestiona. */
@RestController
@RequestMapping(value = "/management/requests", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Not the manager or owner of the property",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The repair request does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The request no longer accepts invitations",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class MaintenanceManagementController {
    private final CurrentActorProvider actors;
    private final ManageMaintenanceRequests management;
    private final RequestPeople people;

    MaintenanceManagementController(CurrentActorProvider actors, ManageMaintenanceRequests management,
            RequestPeople people) {
        this.actors = actors;
        this.management = management;
        this.people = people;
    }

    @GetMapping
    @Operation(summary = "Requests of every property the current manager administers, newest first")
    List<BoardItemResponse> board() {
        var requests = management.board(actors.currentActor());
        var names = people.namesFor(requests);
        return requests.stream()
                .map(request -> new BoardItemResponse(request.id(), request.propertyId(), request.propertyName(),
                        request.propertyCity(), request.specialty(), request.title(), request.status(),
                        request.urgencyLevel(), request.slaDeadline(), request.slaState(),
                        names.get(request.requestedByUserId()), request.assignedFixerUserId(),
                        names.get(request.assignedFixerUserId()), request.createdAt()))
                .toList();
    }

    @PostMapping(value = "/{requestId}/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Invite up to five verified fixers of the specialty to quote the request")
    InvitationResponse invite(@PathVariable UUID requestId, @Valid @RequestBody InvitationRequest body) {
        return new InvitationResponse(management.invite(actors.currentActor(), requestId, body.fixerUserIds(),
                body.note()));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record InvitationRequest(@NotEmpty @Size(max = 5) List<@NotNull UUID> fixerUserIds, @Size(max = 500) String note) {
    }

    @Schema(requiredProperties = {"invitedFixerUserIds"})
    record InvitationResponse(List<UUID> invitedFixerUserIds) {
    }

    @Schema(requiredProperties = {"requestId", "specialty", "title", "status", "urgencyLevel", "slaState", "createdAt"})
    record BoardItemResponse(UUID requestId, UUID propertyId, @Schema(types = {"string", "null"}) String propertyName,
            @Schema(types = {"string", "null"}) String propertyCity, Specialty specialty, String title,
            RepairRequestStatus status, UrgencyLevel urgencyLevel,
            @Schema(types = {"string", "null"}) Instant slaDeadline, SlaState slaState,
            @Schema(types = {"string", "null"}) String requestedByName,
            @Schema(types = {"string", "null"}) UUID assignedFixerUserId,
            @Schema(types = {"string", "null"}) String assignedFixerName, Instant createdAt) {
    }
}
