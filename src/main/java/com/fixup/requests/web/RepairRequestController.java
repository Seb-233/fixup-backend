package com.fixup.requests.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.application.CreateRepairRequest;
import com.fixup.requests.application.GetRepairRequest;
import com.fixup.requests.application.ListOpenRepairRequests;
import com.fixup.requests.application.ListOwnRepairRequests;
import com.fixup.requests.application.NewRepairRequest;
import com.fixup.requests.application.RepairRequestSummary;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** FR-UC-18: solicitudes de reparación. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid specialty, missing field or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, missing role or request not visible to the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The repair request does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The transition does not apply to the current state of the request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class RepairRequestController {
    private final CurrentActorProvider actors;
    private final CreateRepairRequest createRequest;
    private final ListOwnRepairRequests listOwn;
    private final ListOpenRepairRequests listOpen;
    private final GetRepairRequest getRequest;

    RepairRequestController(CurrentActorProvider actors, CreateRepairRequest createRequest,
            ListOwnRepairRequests listOwn, ListOpenRepairRequests listOpen, GetRepairRequest getRequest) {
        this.actors = actors;
        this.createRequest = createRequest;
        this.listOwn = listOwn;
        this.listOpen = listOpen;
        this.getRequest = getRequest;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Open a repair request",
            description = "The body carries provisional photo storage keys. No raw image binary content crosses this API.")
    @ApiResponse(responseCode = "201", description = "The request is open and visible to the fixers")
    @ResponseStatus(HttpStatus.CREATED)
    RequestDetailResponse open(@Valid @RequestBody OpenRequest body) {
        return RequestDetailResponse.of(createRequest.execute(actors.currentActor(),
                new NewRepairRequest(body.specialty(), body.title(), body.description(),
                        body.photoKeys() == null ? List.of() : body.photoKeys())));
    }

    @GetMapping("/me")
    @Operation(summary = "List the repair requests opened by the current user")
    @ApiResponse(responseCode = "200", description = "Requests owned by the current user, newest first")
    List<RequestDetailResponse> mine() {
        return listOwn.execute(actors.currentActor()).stream().map(RequestDetailResponse::of).toList();
    }

    @GetMapping("/open")
    @Operation(summary = "List the open requests offered to fixers",
            description = "FR-UC-18: the fixer's inbox. Requires active, verified FIXER and filters by fixer specialties.")
    @ApiResponse(responseCode = "200", description = "Open requests matching fixer specialties, newest first")
    List<OpenRequestSummaryResponse> open() {
        return listOpen.execute(actors.currentActor()).stream().map(OpenRequestSummaryResponse::of).toList();
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "Read one repair request",
            description = "The owner always reads it; a verified compatible fixer reads it while open, or afterwards "
                    + "only when the work was assigned to him.")
    @ApiResponse(responseCode = "200", description = "The request with its description and photo keys")
    RequestDetailResponse detail(@PathVariable UUID requestId) {
        return RequestDetailResponse.of(getRequest.execute(actors.currentActor(), requestId));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record OpenRequest(@NotNull Specialty specialty, @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 2000) String description,
            @Size(max = 6) List<@NotBlank @Size(max = 512) String> photoKeys) {
    }

    @Schema(requiredProperties = {"requestId", "specialty", "title", "createdAt"})
    record OpenRequestSummaryResponse(UUID requestId, Specialty specialty, String title, Instant createdAt) {
        static OpenRequestSummaryResponse of(com.fixup.requests.domain.RepairRequest request) {
            return new OpenRequestSummaryResponse(request.id(), request.specialty(), request.title(), request.createdAt());
        }
    }

    @Schema(requiredProperties = {"requestId", "specialty", "title", "description", "photoKeys",
        "status", "createdAt"})
    record RequestDetailResponse(UUID requestId, Specialty specialty, String title, String description,
            @Schema(description = "Provisional, insecure photo storage keys while the media module is integrated. Will be replaced by evidence IDs.")
            List<String> photoKeys, RepairRequestStatus status,
            @Schema(types = {"string", "null"}) UUID assignedFixerUserId, Instant createdAt) {

        static RequestDetailResponse of(com.fixup.requests.domain.RepairRequest request) {
            return new RequestDetailResponse(request.id(), request.specialty(), request.title(), request.description(),
                    request.photoKeys(), request.status(), request.assignedFixerUserId(), request.createdAt());
        }

        static RequestDetailResponse of(RepairRequestSummary summary) {
            return new RequestDetailResponse(summary.id(), summary.specialty(), summary.title(), summary.description(),
                    summary.photoKeys(), summary.status(), summary.assignedFixerUserId(), summary.createdAt());
        }
    }
}
